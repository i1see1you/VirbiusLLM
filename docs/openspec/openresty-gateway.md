# OpenResty 网关集成（Stretch · 路径 A：编译期拍平）

与 [DESIGN.md §11.5](../DESIGN.md#115-virbius-gatewayapisix--kong-双网关架构)、[scene-registry.md](./scene-registry.md)、[bind-scope.md](./bind-scope.md) 对齐。**非 MVP 验收项**（官方管层仍为 APISIX Must + Kong Stretch）；供 OpenClaw / 纯 nginx 环境 PoC。

| 项目 | 说明 |
|------|------|
| 状态 | **P0+P1 已实现**（2026-05）；P2 bind_scope / SSE 未做 |
| 代码 | `virbius-gateway/plugins/openresty/access.lua`、`virbius-gateway/lib/*`、`GatewayOpenrestyEmitter` |
| 示例 | [examples/gateway/openresty-poc/0.1.0](../../examples/gateway/openresty-poc/0.1.0/README.md) |

---

## 1. 定位

| 对比 | APISIX / Kong（MVP） | OpenResty（Stretch） |
|------|----------------------|----------------------|
| 配置壳 | Service / Route 插件 JSON（etcd / decK） | Compiler 拍平 **`effective-*.json`** + `locations.conf` |
| 三层 merge | 平台运行时 `Route > Service > Global` | **编译期** `VirbiusConfigMerger` |
| 共用 | `virbius-gateway/lib/*`、`gateway-agent`、Evaluate 契约 | 同左 |
| scene / vars | 运行时读 JSON + HTTP | 同左 |

---

## 2. 管侧数据真源（与 APISIX 一致）

**名单、`context_bindings`、`scene_registry` 的运行时真源**为 **virbius-control** `ArtifactService` 写入的本地文件（**非 Redis**）：

```text
{VIRBIUS_DATA_DIR}/gateway/default-access-lists.json
{VIRBIUS_DATA_DIR}/gateway/default-scene-registry.json
```

- 默认 `VIRBIUS_DATA_DIR=./data`
- control 启动时 `AccessListBootstrap` → `refreshArtifacts("default")`
- 改名单 / metadata 后 Admin `syncRules` 或规则 rollout 触发再 refresh

**Redis** 仅用于 gateway-agent **累计计数**（`cumulatives`），不参与 `context_bindings` 解析。

---

## 3. 路径对齐（方案 A · 推荐）

OpenResty 比 APISIX **多一层** effective JSON；`lists_file` / `scene_registry_file` 在 effective 的 `virbius` 块内，由 compiler 生成。

**对齐原则**：OpenResty 与 APISIX 的 `lists_file`、`scene_registry_file` **必须指向 control 写出的同一对文件**，运行时行为才一致。

### 3.1 本地 PoC 步骤

```bash
# 1. control 写 data/gateway/*.json
./scripts/run-local.sh

# 2. 编译 OpenResty（默认已 control-data 布局）
./scripts/compile-openresty-poc.sh

# 3. 核对
grep lists_file staging/default/0.1.0/gateway/openresty/effective-*.json
# → .../data/gateway/default-access-lists.json
```

### 3.2 Compiler 参数

| 参数 | PoC 推荐值 | 含义 |
|------|------------|------|
| `-o` | `staging/default/0.1.0` | 写出 `gateway/openresty/locations.conf`、effective JSON |
| `--deploy-prefix` | `$PWD/data`（= `VIRBIUS_DATA_DIR`） | effective 内 **lists/scene 路径** 的根 |
| `--deploy-layout` | `control-data` | `{prefix}/gateway/...`（**不**追加 `{bundle_version}`） |
| `--gateway` | `openresty` | 仅 OpenResty 产物 |

```bash
java -jar virbius-compiler/target/virbius-compiler-0.1.0-SNAPSHOT.jar \
  -i examples/poc-default-bundle.yaml \
  -o staging/default/0.1.0 \
  --target=gateway --gateway=openresty \
  --deploy-prefix="$PWD/data" \
  --deploy-layout=control-data
```

**生产 staged 布局**（整包发布目录，无 control 同机 data 时）：

```bash
--deploy-prefix=/etc/virbius --deploy-layout=staged
# → lists_file: /etc/virbius/{version}/gateway/default-access-lists.json
# PublishOrchestrator 需把 control 快照推到该路径
```

### 3.3 运行时读路径分工

```text
access.lua
  ├─ $virbius_effective     → staging/.../openresty/effective-*.json（插件开关、agent_url）
  ├─ effective.lists_file   → data/gateway/default-access-lists.json（control）
  └─ effective.scene_registry_file → data/gateway/default-scene-registry.json（control）
```

改名单后 **只 refresh control**，**不必**重新 compile OpenResty（除非改 evaluate / upstream / routes）。

---

## 4. APISIX 对照

| 项 | APISIX | OpenResty |
|----|--------|-----------|
| 插件配置 | `apisix-service-*.json` → `plugins.virbius-guard` | `effective-*.json` → `virbius` |
| lists / scene 路径 | 写在 service/route 插件字段 | 写在 effective `virbius` |
| 本地 PoC 路径 | `./data/gateway/...` 或挂载到容器路径 | `--deploy-prefix=./data --deploy-layout=control-data` |
| Compiler 生成 APISIX 路径 | 当前硬编码 `/usr/local/apisix/...`（本地需手改或挂载） | 见 §3 |

样例 APISIX 见 [examples/gateway/poc-default/0.1.0](../../examples/gateway/poc-default/0.1.0/README.md)。

---

## 5. 编译产物

```text
staging/{tenant}/{version}/gateway/
├── openresty/
│   ├── manifest.json
│   ├── upstreams.conf
│   ├── locations.conf
│   └── effective-{routeKey}.json
├── scene-registry-{tenant}.json    # compiler 副本；PoC 运行时读 control 的 data/gateway/
└── apisix-*.json                   # --gateway=apisix|all 时
```

`effective-*.json` 含 `virbius`（拍平后配置）与 `merge_trace`（调试用）。

---

## 6. 已实现能力（P0+P1）

| 能力 | 说明 |
|------|------|
| `context_bindings` → vars | 运行时；读 access-lists 内 bindings |
| `scene_registry` 动态解析 | 运行时；读 scene_registry 文件 |
| Global/Service/Route 插件标量 | 编译期 merge → effective |
| `bind_scope` 名单过滤 | **P2** |
| SSE body_filter | **P2** |
| compiler emit access-lists | **未做**；依赖 control ArtifactService |

---

## 7. nginx 最小配置

```nginx
http {
    lua_package_path "/path/to/VirbiusLLM/virbius-gateway/lib/?.lua;;";
    include /path/to/staging/default/0.1.0/gateway/openresty/upstreams.conf;
    server {
        listen 9080;
        include /path/to/staging/default/0.1.0/gateway/openresty/locations.conf;
    }
}
```

环境变量：`VIRBIUS_OPENRESTY_ACCESS_LUA`（compiler 写 `locations.conf` 时解析 access.lua 绝对路径）。

---

## 8. 相关文档

- [POC-SEED-API.md §6](../POC-SEED-API.md) — 名单与 `data/gateway` 产物
- [user-guide.md §4.8](../user-guide.md) — 集成方网关数据路径
- [virbius-gateway/README.md](../../virbius-gateway/README.md)
