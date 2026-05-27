# Groovy L3 脚本契约（PoC）

云侧 `runtime=groovy` 规则在 **virbius-engine** 内沙箱执行；发布前经 **G6** AST 校验（`GroovyL3Validator`）。

## 脚本形态

- 必须定义：`def decide(ctx) { ... }`
- 执行入口：`return decide(ctx)`（引擎自动追加）
- 返回值：`Map`，至少含 `action`（`allow` / `block` / `review` 等）；可选 `would_block`（boolean）

## `ctx` API（白名单）

| 方法 | 说明 |
|------|------|
| `ctx.tenantId()` | 租户 |
| `ctx.sessionId()` | 会话（canary 分桶） |
| `ctx.scene()` | 场景 |
| `ctx.currentRuleId()` | 当前 L3 规则 id |
| `ctx.enforceMode(ruleId)` | `dry_run` / `canary` / `full` / `disabled` |
| `ctx.riskScore(ruleId)` | 规则配置 0–100 |
| `ctx.canaryPercent(ruleId)` | canary 百分比 |
| `ctx.signals()` | 本轮 `L3SignalView` 列表 |
| `ctx.wouldHitBlock()` | 是否有 signal 达到拦截阈值（score≥100 或 suggest=block） |
| `ctx.inCanaryBucket(sessionId, percent)` | canary 分桶 |
| `ctx.vars()` | 只读逻辑变量 Map（RequestContext） |
| `ctx.var("app_id")` | 读取逻辑变量，如名单同步的 `app_id=beta` |

`enforce_mode` 取值与含义见 [DESIGN.md §8.5.0](../DESIGN.md)。

## 默认脚本

见 `virbius-groovy-l3` 模块 `GroovyL3Defaults.DEFAULT_DECIDE_SCRIPT`（种子 `cloud_groovy_l3` 与之对齐）。

## 失败策略

- **发布**：校验失败 → HTTP 400 / publish `409`
- **运行时**：超时/异常 → `degraded=true`，回退 `PolicyDecider` 硬编码逻辑，**fail-open**（不默认 block）

## 安全（F-12）

见 `MVP-OPENSPEC.md` §4.7.1（G1–G7）：禁 import/IO/Runtime.exec、body≤32KB、单次≤50ms。
