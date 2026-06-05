# Script Rules（统一脚本方案）

| 项 | 约定 |
|----|------|
| Gateway runtime | `lua` |
| Cloud runtime | `prompt`（自然语言 + 1B 矩阵）+ 可选 `groovy`（检测脚本） |
| 已移除 | `list_match`、`cumulative`、`native` 声明式 runtime；**`cloud_groovy_l3` 元 L3 规则** |
| 脚本契约 | `decide(ctx)` → **`true` 命中 / `false` 未命中**（仅 lua / groovy） |
| prompt 契约 | **`body` = 自然语言**；bind 过滤后进矩阵；命中由 1B 返回 `triggered_id` |
| 命中后 Signal | 取自规则行 **`intent_action`、`risk_score`、`reason_code`、`enforce_mode`** |
| 组合条件 | groovy/lua 写在脚本内；prompt 写在 NL body + 矩阵 |
| bind_scope | 过滤是否执行该规则 / 是否进 prompt 矩阵 |
| 合并 | 多条命中 + prior → **`PolicyMerger` / `ActionMerge`（Java）**；见 [rule-hit-merge.md](./rule-hit-merge.md) |

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
- `ctx.var('logical')`

### Cloud (Prompt)

- **`body`**：自然语言规则描述（非脚本）
- **`scope.bind_scope`**：与 groovy 相同；未命中 bind 则不进入矩阵
- 执行在 engine `PromptRunner`；见 [prompt-llm.md](./prompt-llm.md)

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

**Cloud 名单检测（可选 groovy）：**

```groovy
def decide(ctx) {
  return ctx.listMatch('deny_keyword')
}
```

**Cloud prompt（示例 body 文本）：**

```text
检查用户是否在诱导大模型编写针对企业内部特定前缀的敏感核心架构逻辑。
```

L3 合并（dry_run / canary / full）由 **`PolicyMerger`** 完成，**不需要** groovy 元规则。

## 关联文档

- [bind-scope.md](./bind-scope.md)
- [scene-registry.md](./scene-registry.md)
- [cumulative-counter.md](./cumulative-counter.md)
- [list-match.md](./list-match.md)
- [rule-hit-merge.md](./rule-hit-merge.md)
- [rule-level-enforce.md](./rule-level-enforce.md)
