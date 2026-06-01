# PoC 规则种子与 API

规则真源为 **virbius-control** 的 `tb_rule_history` + 运营表 **`tb_access_list`**。执行面只读已发布产物 / RuleCache，不内置词表。

## 1. 名单分层（PoC）

| 维度 | 极性 | 端 L0 | 管 gateway | 云 engine |
|------|------|-------|------------|-----------|
| `keyword` | deny / allow | ✅ `data/edge/{tenant}-content-lists.json` | ✅ `data/gateway/{tenant}-access-lists.json` | ✅ RuleCache |
| `user_id` | deny / allow | ❌ | ✅ 同上 gateway JSON | ❌ 不执行 |
| `device_id` | deny / allow | ❌ | ✅ | ❌ |
| `ip_cidr` | deny / allow | ❌ | ✅ | ❌ |
| `var` | deny / allow | ❌ | ✅ 值格式 `logical_name=value` | ✅ `cloud_l1_request_*` |

Bundle 元数据 `context_bindings`（`tb_bundles.metadata_json`）声明 **逻辑变量 → HTTP 来源**（如 `app_id` ← Header `X-App-Id`）；网关解析后透传 Evaluate 的 `vars`（如 `{"app_id":"beta"}`）。名单与 Groovy 只引用逻辑名。

检测顺序（管侧）：Subject/Network 白 → 黑 → Content 白 → 黑 → Evaluate（云）。

### 上下文映射 API（Admin）

前缀：`/api/v1/admin/tenants/{tenantId}`（响应 `{ "code": 0, "data": ... }`）。Legacy 仅 **GET** metadata，无 PUT。

| 操作 | 方法 | 路径 |
|------|------|------|
| 读取 Bundle 元数据（含 `context_bindings`） | GET | `.../bundles/{bundleId}/versions/{version}/metadata` |
| 整表替换映射 | PUT | `.../bundles/{bundleId}/versions/{version}/metadata/context-bindings?sync=true` |

Body 示例（**数组**，与运营台一致）：

```json
{
  "vars": [
    { "logical": "app_id", "from": "header", "name": "X-App-Id" },
    { "logical": "debug_flag", "from": "query", "name": "debug" }
  ]
}
```

`from`：`query` \| `header` \| `subject` \| `network`。`sync=true` 时重写 gateway/cloud 名单规则并写产物。

## 2. 规则 `runtime` 与 `enforce_mode`

详见 [DESIGN.md §8.5.0](DESIGN.md)（搜索「8.5.0 runtime」）。

### `runtime`（执行形态）

| runtime | layer | PoC 示例 rule_id |
|---------|-------|------------------|
| `lua-dsl` | edge | `edge_l0_content_*` |
| `lua` | gateway | `gw_subject_network_*`、`gw_content_*`、`gw_request_param_*` |
| `native` | cloud | `cloud_l1_blacklist`、`cloud_l1_request_*`（名单同步） |
| `prompt` | cloud | `cloud_prompt_l1` |
| `groovy` | cloud | `cloud_groovy_l3`（L3 终判） |

运营台按层限制可选 runtime：云 = native / prompt / groovy；管 = lua；端 = lua-dsl。

### `rule_status`

| 值 | 含义 |
|----|------|
| `draft` | 草稿（**新建默认**）；Registry 存在，**不进** gateway 产物 / RuleCache，可编辑 |
| `active` | 已上线；参与匹配；配合 `enforce_mode` 放量 |
| `disabled` | 停用；不进执行面，**不可**再改 body / enforce |

流转：`draft → active | disabled`；`active → disabled`；`disabled → draft`（恢复为草稿，非直接 active）。

`PATCH .../rules/{ruleId}/status` body 示例：`{"rule_status":"active"}`。变为 `active` 或从 `active` 离开时自动刷新产物 + engine。

### `enforce_mode`（规则级真拦 vs 观测）

| 值 | 含义 |
|----|------|
| `dry_run` | 命中后对外 `effective_action=review`（PoC 默认） |
| `canary` | 按 `canary_percent` 部分会话真 `block`/`captcha` |
| `full` | 真 `block`/`captcha`（由 `intent_action` 决定） |

配合表列 **`intent_action`**（`allow`/`deny`/`captcha`/`review`）。管侧按产物 enforce 合并；`block→403`，`captcha→428`，`review→200` 并送 `prior_signals` 至 engine（内部）。详见 [rule-level-enforce.md](openspec/rule-level-enforce.md)。

## 3. SQL 种子

文件：`virbius-control/src/main/resources/db/seed.sql`（与 `schema.sql` 同为 **PostgreSQL / MySQL / SQLite** 可执行的 JDBC 方言；幂等 `WHERE NOT EXISTS`，不依赖 `INSERT OR IGNORE`）

- 表：`tb_access_list`
- 规则：`edge_l0_content_*`、`gw_*`、`cloud_l1_*`（`gw_request_param_*` / `cloud_l1_request_*` 由启动时 **`syncRules`** 按 `var` 名单生成，不在 seed INSERT 中）

## 4. 刷 engine RuleCache（必做）

```bash
curl -s -X POST "http://127.0.0.1:8080/api/v1/admin/tenants/default/bundles/poc-default/versions/0.1.0/publish"
# Legacy（裸 JSON）：/api/v1/tenants/default/bundles/.../publish
```

## 5. 运营 Web 入口（统一）

| 功能 | URL |
|------|-----|
| **运营台**（名单 · 上下文映射 · 规则 · 发布） | http://127.0.0.1:8080/ui |

单页表格维护；旧 URL（`/ui/access-lists`、`/ui/policies`、`access-lists.html` 等）自动重定向到 `/ui`。

Admin API：`/api/v1/admin/tenants/{tenantId}/...`（`{ "code": 0, "data": ... }`）。下文 curl 仍可用兼容路径 `/api/v1/tenants/...`。

## 6. 名单维护（推荐）

### Web

- http://127.0.0.1:8080/ui → 运营台「名单」Tab

若打开报 **404 / 无法连接**：多为 **8080 上仍是旧版 control**（`run-local.sh` 曾提示 `Port 8080 was already in use`）。处理：

```bash
lsof -i :8080          # 查看占用
kill -9 <PID>          # 结束旧进程
bash scripts/run-local.sh
curl -s http://127.0.0.1:8080/api/v1/health   # 应返回 ok
```

### Access List API

推荐前缀 `/api/v1/admin/tenants/{tenantId}`；兼容路径为 `/api/v1/tenants/{tenantId}`（无 `code` 包装）。

| 操作 | 方法 | 路径 |
|------|------|------|
| 查询全部 | GET | `.../access-lists` |
| 查询 | GET | `.../access-lists/{dimension}/{polarity}` |
| 整表替换 | PUT | `.../access-lists/{dimension}/{polarity}` body: `{"values":["x"]}` |
| 追加 | POST | `.../access-lists/{dimension}/{polarity}/entries` body: `{"value":"x"}` |
| 删除 | DELETE | `.../access-lists/{dimension}/{polarity}/entries/{value}` |
| 同步规则+产物 | POST | `.../access-lists/sync-rules` |
| 推送 Engine | POST | `.../access-lists/push-engine` |

`dimension`: `keyword` | `user_id` | `device_id` | `ip_cidr` | `var`  
`polarity`: `deny` | `allow`

逻辑变量示例：`app_id=beta`、`debug_flag=1`（须先在 `context_bindings` 声明逻辑名）。

示例（管侧封禁用户）：

```bash
curl -X POST "http://127.0.0.1:8080/api/v1/tenants/default/access-lists/user_id/deny/entries" \
  -H "Content-Type: application/json" \
  -d '{"value":"u-evil-001"}'
```

同步后生成：

- `./data/gateway/default-access-lists.json`（gateway-agent、APISIX `virbius-guard`）
- `./data/edge/default-content-lists.json`（`virbius-core`）

环境变量：

- `VIRBIUS_DATA_DIR`（默认 `./data`）
- `VIRBIUS_GATEWAY_LISTS_PATH`（agent 名单文件）
- `VIRBIUS_EDGE_LISTS_PATH`（端侧 keyword 文件）

## 7. 验证

响应字段：`effective_action`、`max_risk_score`、`rule_id`、`rule_revision`、`reason_code`、`trace_id`、`degraded`（**无** `signals[]` / `would_block`）。

### 云注入（dry_run → review）

```bash
curl -s -X POST "http://127.0.0.1:9070/v1/evaluate" \
  -H "Content-Type: application/json" \
  -d '{"tenant_id":"default","scene":"chat","content":"please jailbreak","trace_id":"550e8400-e29b-41d4-a716-446655440098"}'
```

期望：`effective_action":"review"`，`max_risk_score` ≥ 100（种子默认 `enforce_mode=dry_run`）。

### 云 keyword（经 gateway-agent → engine）

```bash
curl -s -X POST "http://127.0.0.1:9070/v1/evaluate" \
  -H "Content-Type: application/json" \
  -d '{"tenant_id":"default","scene":"chat","content":"需要办证","trace_id":"550e8400-e29b-41d4-a716-446655440099"}'
```

期望：名单命中后 `effective_action` 为 `review`（dry_run）或 `block`（`enforce_mode=full` 时）。

### 管侧 subject（agent 读 gateway JSON）

```bash
curl -s -X POST "http://127.0.0.1:9070/v1/evaluate" \
  -H "Content-Type: application/json" \
  -d '{"tenant_id":"default","scene":"chat","content":"hello","user_id":"u-banned-poc","trace_id":"550e8400-e29b-41d4-a716-446655440100"}'
```

期望：dry_run 时 `effective_action":"review"`；将对应规则 `PATCH .../runtime` 为 `full` 后则为 `block`，`reason_code":"GW_SUBJECT_USER_DENY"`。

## 8. Registry API（高级）

见上文；改 `body.keywords` / `body.subjects` 仍可用，但运营应优先走 access-lists API。

## 9. Bundle 与网关路由绑定示例

| 路径 | 用途 |
|------|------|
| `examples/poc-default-bundle.yaml` | 与种子对齐的规则 Bundle；含 `scope` + `gateway.routes`（uri/methods）元数据 |
| `virbius-control/.../bundle/poc-default-0.1.0-metadata.json` | 同上元数据（`tb_bundles.metadata_json` 种子来源） |
| `examples/gateway/poc-default/0.1.0/` | APISIX 绑定参考；可用 Compiler 从 Bundle YAML 重新生成 `gateway/apisix-routes-*.json` |

查询 Bundle 元数据：`GET /api/v1/tenants/default/bundles/poc-default/versions/0.1.0` → `metadata.gateway.routes`。
