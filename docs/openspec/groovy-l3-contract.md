# 云侧 Groovy 检测脚本契约（PoC）

> **命名说明**：历史称「Groovy L3」；**L3 合并已改为 Java `PolicyMerger`**（见 [rule-hit-merge.md](./rule-hit-merge.md)）。本文档仅描述 **`runtime=groovy` 可选检测脚本**。

云侧 `runtime=groovy` 规则在 **virbius-engine** 内沙箱执行；发布前经 **G6** AST 校验（`GroovyL3Validator`）。

## 脚本形态

- 必须定义：`def decide(ctx) { ... }`
- 执行入口：`return decide(ctx)`（引擎自动追加）
- 返回值：**`boolean`** — `true` 命中 / `false` 未命中
- 命中后 Signal 的 `intent_action` / `risk_score` / `reason_code` / `enforce_mode` **取自规则行**；不由脚本返回 action

**已废弃**：返回 `Map { action: ... }` 的元 L3 终判脚本（原 `cloud_groovy_l3`）。

## `ctx` API（白名单）

| 方法 | 说明 |
|------|------|
| `ctx.tenantId()` | 租户 |
| `ctx.sessionId()` | 会话（canary 分桶） |
| `ctx.scene()` | 场景 |
| `ctx.currentRuleId()` | 当前规则 id |
| `ctx.enforceMode(ruleId)` | `dry_run` / `canary` / `full` / `disabled` |
| `ctx.riskScore(ruleId)` | 规则配置 0–100 |
| `ctx.canaryPercent(ruleId)` | canary 百分比 |
| `ctx.signals()` | 本轮 `L3SignalView` 列表（含 prior） |
| `ctx.wouldHitBlock()` | 是否已有 block 级 signal（高级脚本可用） |
| `ctx.inCanaryBucket(sessionId, percent)` | canary 分桶 |
| `ctx.vars()` / `ctx.var("app_id")` | 逻辑变量 |
| `ctx.listMatch(name)` / `ctx.listMatch(name, value)` | 名单匹配 |
| `ctx.getCumulative(name)` | 累计读 |

`enforce_mode` 与对外 `effective_action` 的关系见 [rule-level-enforce.md](./rule-level-enforce.md)；**默认租户可不配置任何 groovy 规则**，仅 prompt + 代码合并即可。

## 示例

**名单检测（推荐）：**

```groovy
def decide(ctx) {
  return ctx.listMatch('deny_keyword')
}
```

## 失败策略

- **发布**：校验失败 → HTTP 400 / publish `409`
- **运行时**：超时/异常 → 该规则 skip + WARN，**fail-open**（不默认 block）

## 安全（F-12）

见 `MVP-OPENSPEC.md` §4.7.1（G1–G7）：禁 import/IO/Runtime.exec、body≤32KB、单次≤50ms。
