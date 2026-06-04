# 规则表单 Authoring + 模拟调试（P1 / P2）

| 状态 | **已实现**（PoC） |
|------|-------------------|
| 关联 | [script-rules.md](./script-rules.md)、[bind-scope.md](./bind-scope.md)、[rule-rollout.md](./rule-rollout.md)、[registry.openapi.yaml](./registry.openapi.yaml) |

## 1. 设计原则

1. **执行真源唯一**：gateway / engine 只读 `scope`（bind）+ `body`（脚本）。
2. **不持久化 authoring**：DB 无 `authoring_json`；条件 AST 仅在内存中流转。
3. **保存即编译**：简单模式保存前必须把表单 compile 成 `body`。
4. **加载即反解析**：`scope` 直填 bind 表单；`body` 经 `parse-condition` 填条件构建器，失败则 advanced。
5. **模拟不写入**：simulate 使用 mock 累计，不写 audit、不 ingest Redis。

## 2. 运营台 UX

| 区域 | 来源 | 说明 |
|------|------|------|
| 生效范围 | `scope.bind_scope` + `scope.bind_ref` | 与 bind-scope 一致 |
| 触发条件 | 简单：条件构建器；高级：`body` 脚本 | lua / groovy 规则 |
| 脚本预览 | `compile-condition` | 简单模式只读 |
| 模拟 | `simulate` | vars → scene → bind → decide → signal |

**编辑模式**

- `simple`：名单 / 累计条件行 + 菜谱；保存时 compile。
- `advanced`：直接编辑 `body`；加载时 parse 失败则保持 advanced。

## 3. 条件 AST（内存）

```json
{ "type": "list_match", "list_name": "deny_keyword", "value_source": "content" }
```

```json
{ "type": "cumulative", "cumulative_name": "user_req_1h", "compare": "gte", "threshold": 120 }
```

```json
{ "op": "and", "children": [ /* leaf */ ] }
```

Cloud L3 根节点：

```json
{ "type": "l3_aggregate", "list_name": "deny_keyword" }
```

编译产物带标记 `-- virbius:generated v1`（Lua）或 `// virbius:generated v1`（Groovy）。

## 4. Admin API

基址：`POST /api/v1/admin/tenants/{tenantId}/rules/...`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/compile-condition` | `{ layer, runtime, condition }` → `{ script, warnings }` |
| POST | `/parse-condition` | `{ layer, runtime, script }` → `{ parseable, condition, inferred_recipe, warnings }` |
| GET | `/recipes?layer=` | 静态菜谱列表 |
| POST | `/simulate` | 见 §5 |
| POST | `/rules` | `UpsertRuleRequest` 可选 `editor_mode` + `condition`；服务端 compile 后存 `body` |

## 5. Simulate 请求与 trace

```json
{
  "editor_mode": "simple",
  "condition": { "type": "list_match", "list_name": "deny_keyword", "value_source": "content" },
  "rule": {
    "rule_id": "draft-preview",
    "bundle_id": "poc-default",
    "layer": "gateway",
    "runtime": "lua",
    "scope": { "bind_scope": "global" },
    "body": "...",
    "intent_action": "deny",
    "risk_score": 100,
    "rollout_state": "dry_run"
  },
  "fixture": {
    "route_uri": "/v1/chat/completions",
    "headers": { "X-App-Id": "medical-prod" },
    "query": { "mode": "clinical" },
    "content": "test prompt",
    "overrides": {
      "cumulative": { "user_req_1h": 150 },
      "force_list_hit": false
    },
    "prior_signals": [
      { "rule_id": "r1", "intent_action": "deny", "risk_score": 100, "reason_code": "X" }
    ]
  },
  "options": { "cumulative_source": "mock" }
}
```

**steps**：`vars` → `scene` → `bind` → `decide` → `signal`

**effective_action**（summary / signal 步）：

| hit | rollout_state | intent | effective_action |
|-----|---------------|--------|------------------|
| false | * | * | `allow` |
| true | `dry_run` | deny | `review` |
| true | `canary`/`full` | deny | `block` |

L3 Groovy simulate 需在 fixture 提供 `prior_signals` 供 `wouldHitBlock()`。

Advanced Lua：仅当脚本可 parse 为 AST 时可 simulate；否则返回错误。

## 6. 菜谱（不持久化）

| recipe_id | layer | bind_scope | 条件 |
|-----------|-------|------------|------|
| `gateway.list.deny` | gateway | global | list_match |
| `gateway.cum.rate_limit` | gateway | global | cumulative |
| `gateway.route.scene_list` | gateway | route + scenes | list_match |
| `cloud.list.deny` | cloud | global | list_match |
| `cloud.cum.rate_limit` | cloud | global | cumulative |
| `cloud.l3.aggregate` | cloud | global | l3_aggregate |

## 7. 实现映射

| 模块 | 路径 |
|------|------|
| ConditionCompiler / Parser / Evaluator | `virbius-control/.../ruleauthoring/` |
| RuleAuthoringService | `virbius-control/.../service/RuleAuthoringService.java` |
| RuleSimulateService | `virbius-control/.../service/RuleSimulateService.java` |
| RuleService.resolveBody | 简单模式 upsert 时 compile |
| 运营台 | `virbius-control/src/main/resources/static/ops.html` |
