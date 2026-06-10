# OpenResty PoC（路径 A：编译期拍平）

与 [virbius-gateway/README.md](../../../virbius-gateway/README.md)、[用户使用手册 §4.7](../../../docs/user-guide.md) 对齐。由 `scripts/compile-openresty-poc.sh` 从 `examples/poc-default-bundle.yaml` 生成 nginx 配置；**名单与 context_bindings 真源**仍为 **virbius-control** 写入的 `data/gateway/`。

## 前置条件

- OpenResty（nginx + lua）
- `./scripts/run-local.sh`（control + gateway-agent `:9070` + engine）
- 可选 upstream：Ollama `:11434`（compiler 默认 upstream）

## 本地联调（方案 A：effective 指 control 的 data/gateway）

```bash
# 1. control → refreshArtifacts → data/gateway/*.json
./scripts/run-local.sh

# 2. 编译 OpenResty nginx 配置（默认 DEPLOY_PREFIX=./data, deploy-layout=control-data）
./scripts/compile-openresty-poc.sh

# 3. 核对 effective 内路径
grep -E 'lists_file|scene_registry' staging/default/0.1.0/gateway/openresty/effective-*.json
```

产物分工：

| 路径 | 内容 | 谁维护 |
|------|------|--------|
| `staging/.../gateway/openresty/` | `locations.conf`、`upstreams.conf`、`effective-*.json` | compiler |
| `data/gateway/default-access-lists.json` | `context_bindings`、`lists[]`、… | **control** |
| `data/gateway/default-scene-registry.json` | `scene_registry` | **control** |

改名单后 control refresh 即可；**不必**重新 compile（除非改 evaluate、upstream、routes）。

自定义 data 目录：

```bash
VIRBIUS_DATA_DIR=/path/to/data DEPLOY_PREFIX=/path/to/data ./scripts/compile-openresty-poc.sh
```

## nginx 配置

见 [nginx.conf.template](./nginx.conf.template)（替换 `__VIRBIUS_REPO__` 为仓库根路径）。

```nginx
http {
    lua_package_path "/path/to/VirbiusLLM/virbius-gateway/lib/?.lua;;";
    include staging/default/0.1.0/gateway/openresty/upstreams.conf;
    server {
        listen 9080;
        include staging/default/0.1.0/gateway/openresty/locations.conf;
    }
}
```

`locations.conf` 中 `$virbius_effective` 指向 **staging** 下的 effective JSON；effective 内的 `lists_file` 指向 **data/gateway/**（见上表）。

## 验证

```bash
curl -sS -X POST http://127.0.0.1:9080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'X-App-Id: medical-prod' \
  -d '{"model":"llama3","messages":[{"role":"user","content":"hello"}]}'
```

`X-App-Id: medical-prod` + `?mode=clinical` → scene `medical-prod_clinical`（见 [用户使用手册 §4.7](../../../docs/user-guide.md)）。

## 与 APISIX PoC 对齐

| 项 | APISIX | OpenResty |
|----|--------|-----------|
| 插件配置 | service/route JSON | effective JSON |
| lists / scene 文件 | `./data/gateway/...`（PoC） | 同左（`control-data` layout） |
| scene 解析 | `virbius-guard` + 共用 `lib/` | `access.lua` + 共用 `lib/` |

APISIX 样例：[../poc-default/0.1.0/README.md](../poc-default/0.1.0/README.md)

## 能力边界（P0+P1）

| 功能 | 状态 |
|------|------|
| `context_bindings` → vars | ✅ |
| `scene_registry` 动态解析 | ✅ |
| Global/Service/Route 插件配置拍平 | ✅ |
| `bind_scope` 名单过滤 | P2 |
| SSE 输出审计 | P2 |
