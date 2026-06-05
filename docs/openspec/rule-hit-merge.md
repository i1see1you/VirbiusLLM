# 规则命中与 L3 合并（定稿）

| 项目 | 说明 |
|------|------|
| 状态 | **与 PoC 代码对齐**（2026-05-20） |
| 关联 | [rule-level-enforce.md](./rule-level-enforce.md)、[prompt-llm.md](./prompt-llm.md)、[script-rules.md](./script-rules.md)、[bind-scope.md](./bind-scope.md) |

---

## 1. 原则

1. **检测与合并分离**：`prompt` / `lua` / `groovy`（检测脚本）只负责「是否命中」并产 **Signal**；请求级 **`effective_action`** 由 engine **`PolicyMerger` → `ActionMerge`（Java 代码）** 统一计算。
2. **无元规则 L3**：**不再**使用种子规则 `cloud_groovy_l3`；旧库若仍存在，engine **跳过**执行（`LegacyPolicyRules`）。
3. **同池合并**：管侧 `prior_signals` + 云侧 prompt / groovy 检测命中 → **同一 Signal 池** → 一次 ActionMerge。
4. **primary 归因**：对外 `rule_id` 为 **真实检出规则**（如 `Rule_201`），无命中时为 `POLICY_ALLOW`。

---

## 2. Evaluate 流水线（virbius-engine）

```text
prior_signals（gateway-agent 内部传入）
  → MatchContext（scene / route_uri / vars.app_id / content / session_id）
  → PromptRunner：bind 过滤 → 矩阵聚合 → 1B → 0..1 Signal
  → ScriptRuleRunner：bind 过滤 → groovy decide(ctx) → 0..N Signal（跳过 cloud_groovy_l3）
  → PolicyMerger.merge(tenant, session, signals)
  → effective_action + primary rule_id + reason_code + max_risk_score
```

管侧串行（不变）：gateway ActionMerge 若已 `block`/`captcha` 则不调云；`review` 时带 `prior_signals` 调 Evaluate。

---

## 3. 各 runtime 职责

| runtime | layer | bind_scope | body | 命中产出 |
|---------|-------|------------|------|----------|
| **prompt** | cloud | ✅ 矩阵构建前过滤 | 自然语言描述 | Signal（`triggered_id` = `rule_id`） |
| **groovy** | cloud | ✅ 执行前过滤 | `decide(ctx)` 脚本（**可选**） | Signal（boolean 命中） |
| **lua** | gateway | ✅ | `decide(ctx)` 脚本 | Signal → 管侧 ActionMerge |
| **L3 合并** | — | — | **无规则 body** | `PolicyMerger` / `ActionMerge` |

**prompt 与 groovy（检测）对齐点**：同一 Signal 字段集、`bind_scope` 语义、同一 ActionMerge 公式；差异仅在检测手段（1B 矩阵 vs Groovy 脚本）。

---

## 4. ActionMerge（请求级）

见 [rule-level-enforce.md §2–§3](./rule-level-enforce.md)：

```text
1. 收集命中集 H（非 allow 意图）
2. allow 短路 → effective_action=allow
3. intent 优先级：deny > captcha > review > allow
4. top 组内 max(risk_score) → pickPrimary → rule_id
5. enforce_mode × intent → effective_action（dry_run→review，canary 分桶，full→block）
```

**已废弃**：用 Groovy 脚本读取 `ctx.wouldHitBlock()` 再做 dry_run/canary 的 **元 L3 规则**（原 `cloud_groovy_l3`）。

---

## 5. 对外响应（扁平）

```json
{
  "effective_action": "review",
  "max_risk_score": 100,
  "rule_id": "Rule_201",
  "rule_revision": 1,
  "reason_code": "SENSITIVE_ARCH",
  "trace_id": "...",
  "degraded": false
}
```

无 cloud 命中：`rule_id=POLICY_ALLOW`，`effective_action=allow`（或与 prior 合并后的结果）。

---

## 6. 运营台

| runtime | 表单 |
|---------|------|
| prompt | `bind_scope` + **自然语言 body**；**无**触发条件 / compile-condition |
| lua / groovy | `bind_scope` + 简单条件表单或高级脚本 |

详见 [rule-authoring.md](./rule-authoring.md)。

---

## 7. 实现映射

| 组件 | 路径 |
|------|------|
| PromptRunner + bind | `virbius-engine/.../eval/PromptRunner.java` |
| Groovy 检测 | `virbius-engine/.../eval/ScriptRuleRunner.java` |
| 合并 | `virbius-engine/.../eval/PolicyMerger.java`、`virbius-policy/.../ActionMerge.java` |
| 跳过 legacy L3 | `virbius-engine/.../eval/LegacyPolicyRules.java` |
| bind 共用 | `virbius-engine/.../eval/RuleScopeSupport.java`、`virbius-policy/BindScope.java` |
