# virbius-gateway

管层数据面：APISIX/Kong 插件 + OpenResty（Stretch）+ 共用 Lua `lib/`。

| 路径 | 说明 |
|------|------|
| `lib/` | 共用：`context_vars`、`scene_registry`、`access_lists`、`uri_match` 等 |
| `plugins/apisix/virbius-guard.lua` | APISIX 插件 |
| `plugins/openresty/access.lua` | OpenResty access（读 compiler 拍平 `effective-*.json`） |
| `core/` | 预留 `rules.lua` 框架 |

详细设计：[DESIGN.md §11.5](../docs/DESIGN.md)、[用户使用手册 §4.7](../docs/user-guide.md)

## 管侧 JSON 真源（APISIX 与 OpenResty 共用）

运行时 **不读 Redis** 解析 `context_bindings` / 名单条目；读 **virbius-control** 写入：

```text
{VIRBIUS_DATA_DIR}/gateway/{tenant}-access-lists.json      # bindings + lists + cumulatives + script_rules
{VIRBIUS_DATA_DIR}/gateway/{tenant}-scene-registry.json
```

默认 `./data/gateway/`（`./scripts/run-local.sh` 启动后自动 `refreshArtifacts`）。

| 网关 | 路径配置位置 |
|------|----------------|
| **APISIX** | `plugins.virbius-guard.lists_file` / `scene_registry_file`（service JSON） |
| **OpenResty** | `effective-*.json` → `virbius.lists_file` / `scene_registry_file`（compiler 生成） |

**对齐**：两边路径字符串指向 **同一文件** 时，scene、`app_id`、名单行为一致。

## APISIX

插件启动时将 `plugins/apisix/../../lib` 加入 `package.path`；可设 `VIRBIUS_GATEWAY_LIB`。

PoC 样例：[examples/gateway/poc-default/0.1.0](../examples/gateway/poc-default/0.1.0/README.md)

本地 `lists_file` 示例：`./data/gateway/default-access-lists.json`

## OpenResty（Stretch · 路径 A）

```bash
./scripts/run-local.sh              # 1. data/gateway/*.json
./scripts/compile-openresty-poc.sh  # 2. staging/.../openresty/*.conf + effective
```

- nginx include：`staging/default/0.1.0/gateway/openresty/locations.conf`
- 名单 / scene：`data/gateway/`（`--deploy-layout=control-data`）

见 [examples/gateway/openresty-poc/0.1.0](../examples/gateway/openresty-poc/0.1.0/README.md)
