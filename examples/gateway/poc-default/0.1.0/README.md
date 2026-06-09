# PoC 网关路由绑定样例（poc-default @ 0.1.0）

与 [DESIGN.md §11.4 / §8.10.3](../../../docs/DESIGN.md) 对齐：**Global → Service（tenant）→ Route（scene）** 三层叠加 `virbius-guard`，规则正文来自 Registry bundle `poc-default` / `0.1.0`（见 [poc-default-bundle.yaml](../../../poc-default-bundle.yaml)）。

## 文件清单

| 文件 | APISIX 层 | 说明 |
|------|-----------|------|
| `apisix-global-rules.json` | Global | 全局限流、IP 策略占位（不含 L1/L3 云判） |
| `apisix-service-default.json` | Service | `tenant_id=default`，默认 `bundle_version=0.1.0` |
| `apisix-routes-general_chat.json` | Route | `POST /v1/chat/completions` → `scene=general_chat` |
| `apisix-routes-medical_qa.json` | Route | 同 URI + `vars` 选 `medical_qa`（优先级高于默认路由） |
| `kong-services-default.yaml` | Service | Kong 等价 Service（**非** APISIX JSON 直拷） |
| `kong-routes-general_chat.yaml` | Route | Kong 等价主路由 |

**从 Bundle 生成 Route（推荐）**：

```bash
mvn -q -pl virbius-compiler -am package -DskipTests
java -jar virbius-compiler/target/virbius-compiler-*-SNAPSHOT.jar \
  -i examples/poc-default-bundle.yaml -o /tmp/virbius-out
ls /tmp/virbius-out/gateway/apisix-routes-*.json
```

`uri` / `methods` 来自 Bundle 元数据 `gateway.routes`（与 `tb_bundles.metadata_json` 一致）。

发布编排（目标态）由 Compiler 写入 `staging/{tenant}/{version}/gateway/`，再由 `EtcdAdapter` / `KongDeckAdapter` 推送；本目录 JSON 亦可手工导入验证插件行为。

## 绑定关系

```text
请求 POST /v1/chat/completions
  → [Global] limit-count / ip-restriction
  → [Service virbius-svc-default] tenant=default, bundle_version=0.1.0, agent_url
  → [Route] 按 URI / vars 定 scene；serverless 注入 X-Virbius-Scene（禁止信任客户端伪造）
  → virbius-guard：本地 access-lists → gateway-agent /v1/evaluate → 上游 LLM
```

冲突时 **Route > Service > Global**（`virbius-guard` 的 `scene` / `evaluate` 以 Route 为准）。

## 与 Bundle 规则映射

| layer | rule_id（poc-default） | 执行位置 |
|-------|------------------------|----------|
| edge | `edge_l0_content_deny` | 端侧 CDN / virbius-core（非本目录） |
| gateway | `gw_subject_network_deny`, `gw_content_deny` | `lists_file` JSON + 插件本地匹配 |
| cloud | `cloud_l1_blacklist`, `cloud_prompt_l1`, `Rule_201`–`Rule_203` | gateway-agent → virbius-engine |

Route 只决定 **是否 evaluate、scene、fail_mode**；Prompt / Groovy 仅在 engine 执行。

## 本地导入（APISIX Admin API 示例）

假设 APISIX Admin 监听 `http://127.0.0.1:9180`，且已注册 `virbius-guard` 插件。

```bash
APISIX_ADMIN=http://127.0.0.1:9180/apisix/admin
KEY=your-admin-key
DIR="$(cd "$(dirname "$0")" && pwd)"

# 1) Global
curl -s -X PUT "$APISIX_ADMIN/global_rules/virbius-platform-global-1" \
  -H "X-API-KEY: $KEY" -H "Content-Type: application/json" \
  -d "$(jq '.[0]' "$DIR/apisix-global-rules.json")"

# 2) Service
curl -s -X PUT "$APISIX_ADMIN/services/virbius-svc-default" \
  -H "X-API-KEY: $KEY" -H "Content-Type: application/json" \
  -d @"$DIR/apisix-service-default.json"

# 3) Routes（medical_qa 优先级更高，先写）
curl -s -X PUT "$APISIX_ADMIN/routes/virbius-route-medical-qa" \
  -H "X-API-KEY: $KEY" -H "Content-Type: application/json" \
  -d @"$DIR/apisix-routes-medical_qa.json"

curl -s -X PUT "$APISIX_ADMIN/routes/virbius-route-general-chat" \
  -H "X-API-KEY: $KEY" -H "Content-Type: application/json" \
  -d @"$DIR/apisix-routes-general_chat.json"
```

同步 access-lists（管侧发布或 `AccessListService.syncRules` 产出）：

```text
./data/gateway/default-access-lists.json          # context_bindings + lists（插件、agent）
./data/gateway/default-scene-registry.json        # scene_registry
```

本地 PoC：`./scripts/run-local.sh` 启动 control 后自动 `refreshArtifacts` 写入上述路径（`VIRBIUS_DATA_DIR`，默认 `./data`）。

容器 / APISIX 安装路径示例：

```text
/usr/local/apisix/conf/virbius/data/gateway/default-access-lists.json
```

**OpenResty Stretch**：compiler 使用 `--deploy-prefix=./data --deploy-layout=control-data`，effective 内 `lists_file` / `scene_registry_file` 指向同一 `data/gateway/` 目录。见 [openresty-poc](../../openresty-poc/0.1.0/README.md) 与 [openresty-gateway.md](../../../docs/openspec/openresty-gateway.md)。

## OpenResty（Stretch，可选）

非 MVP 验收；与 APISIX 共用 `data/gateway/` 与 `virbius-gateway/lib/`：

```bash
./scripts/run-local.sh
./scripts/compile-openresty-poc.sh
```

## 验证

1. 启动 PoC：`bash scripts/run-local.sh`（gateway-agent `:9070`，control `:8080`）。
2. 经 APISIX 转发（或直连 agent 冒烟）：

```bash
# 经网关（需 APISIX 已导入上表配置）
curl -s -X POST "http://127.0.0.1:9080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"需要办证"}]}'

# 直连 agent（与 smoke-test 一致；scene 用 general_chat 或当前 PoC 的 chat 均可，engine 暂未按 scene 过滤规则）
curl -s -X POST "http://127.0.0.1:9070/v1/evaluate" \
  -H "Content-Type: application/json" \
  -d '{"tenant_id":"default","scene":"general_chat","content":"需要办证","trace_id":"550e8400-e29b-41d4-a716-446655440099"}'
```

期望：关键词 / 黑名单命中 → `403` 或 `effective_action=block`。

## PoC 说明

- 当前 `virbius-guard` schema 使用 `agent_url`（非 DESIGN 文档中的 `agent_socket`）；sidecar 部署时可改为 Pod 内 `http://127.0.0.1:9070`。
- `scripts/smoke-test.sh` 仍使用 `scene=chat`；本样例按 DESIGN 使用 `general_chat`。engine 尚未按 scene 过滤规则时二者行为相同。
- `sse_mode` 为 Post-MVP 流式审计预留；当前插件未实现，Route 中未配置。
