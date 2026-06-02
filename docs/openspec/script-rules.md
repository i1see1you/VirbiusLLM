# Script Rules（统一脚本方案）

| 项 | 约定 |
|----|------|
| Gateway runtime | `lua` |
| Cloud runtime | `groovy` + `prompt` |
| 已移除 | `list_match`、`cumulative`、`native` 声明式 runtime |
| 脚本契约 | `decide(ctx)` → **`true` 命中 / `false` 未命中** |
| 命中后 Signal | 取自规则行 **`intent_action`、`risk_score`、`reason_code`、`enforce_mode`** |
| 组合条件 | 写在脚本内，如 `listMatch('x') && getCumulative('y') >= 120` |
| 名单 entry | **纯值**；禁止 `logical=value`；名单 dimension 用 `var:logical` 而非 `var` |
| 累计 | `tb_cumulative` 保留；ingest 平台 Phase A；脚本只 **read** `getCumulative(name)` |
| bind_scope | 过滤是否执行该脚本 |
| 合并 | 多条命中 → **ActionMerge** |

## Gateway 产物 JSON

```json
{
  "lists": [{ "list_name": "...", "dimension": "keyword", "entries": [{"value": "..."}] }],
  "cumulatives": [{ "cumulative_name": "...", "dimension": "user_id", "binding_rules": [...] }],
  "script_rules": [{ "rule_id": "...", "body": "function decide(ctx)...", "bind_scope": "global" }]
}
```

不再输出 `cumulative_rules[]`。

## 脚本 API

### Gateway (Lua)

- `listMatch(name)` / `listMatch(name, value)`
- `getCumulative(name)` → 当前窗口计数 `N`（number / long）
- `ctx.content`, `ctx.user_id`, `ctx.var('logical')`

### Cloud (Groovy)

- `ctx.listMatch(name)` / `ctx.listMatch(name, value)`
- `ctx.getCumulative(name)` → 同上，可直接 `>= threshold`
- `ctx.var('logical')`, `ctx.wouldHitBlock()`, `ctx.signals()`

## 示例

**Gateway 关键字 deny：**

```lua
function decide(ctx)
  return listMatch('deny_keyword', ctx.content)
end
```

**Gateway 累计限流：**

```lua
function decide(ctx)
  return getCumulative('user_req_1h') >= 120
end
```

**Cloud 名单 + L3：**

```groovy
def decide(ctx) {
  if (ctx.listMatch('deny_keyword')) return true
  if (!ctx.wouldHitBlock()) return false
  return true
}
```

## 关联文档

- [bind-scope.md](./bind-scope.md)
- [cumulative-counter.md](./cumulative-counter.md)
- [list-match.md](./list-match.md)
- [rule-level-enforce.md](./rule-level-enforce.md)
