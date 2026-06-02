# 规则级 enforce / intent_action — 设计定稿

| 项目 | 说明 |
|------|------|
| 状态 | **已实现**（PoC 执行面）；运营放量见 [rule-rollout.md](./rule-rollout.md)（R1 待实现） |
| 版本 | v2.1（2026-05-20） |
| 关联 | [registry.openapi.yaml](./registry.openapi.yaml)、[rule-rollout.md](./rule-rollout.md)、[groovy-l3-contract.md](./groovy-l3-contract.md)、[gateway-agent.openapi.yaml](./gateway-agent.openapi.yaml)、[audit-event.schema.json](./schemas/audit-event.schema.json) |

---

## 0. 运营面 vs 执行面

| 层 | 字段 | 说明 |
|----|------|------|
| **运营面**（control / Admin） | `rollout_state` + `canary_percent` | 单字段表达配置/放量：`draft` \| `disabled` \| `dry_run` \| `canary` \| `full`。权威：[rule-rollout.md](./rule-rollout.md) |
| **执行面**（gateway / engine 产物） | `enforce_mode` + `canary_percent` | 由 `rollout_state` **导出**；合并算法读本节 §2–§3 |

**已定运营约束**（见 rule-rollout §15）：

- 改 body → **强制 `rollout_state=draft`**，须重走 publish 流程
- **`dry_run → full` 永久禁止**；须 dry_run → canary → … → full
- dry_run→canary **门禁**与 **canary 阶梯**仅 **租户级** `tb_tenant_rollout_policy` 配置（不可 per-rule）
- 审计 **AuditIngest**：默认 **Redis Stream**，可选 **Kafka**（见 rule-rollout §5.7）

PoC 代码仍使用 `rule_status` + `enforce_mode` + `/status` + `/runtime`；R1 迁移至 `rollout_state` + `PATCH .../rollout`。

---

## 1. 设计目标

| 目标 | 做法 |
|------|------|
| 规则级放量 | 每条 `rule_id` 独立 `enforce_mode` / `canary_percent` |
| 意图与执行分离 | 表列 **`intent_action`**（希望动作）+ **`enforce_mode`**（是否真拦）→ 对外 **`effective_action`** |
| 管侧真拦 | gateway 按 `ActionMerge` 合并；`block`/`captcha` 终局不调云 |
| 云侧合并 | engine `PolicyMerger` 对命中集按 `intent_action` 优先级合并 |
| 对外扁平响应 | HTTP/gRPC **不返回** `signals[]`；仅 `effective_action`、`max_risk_score`、primary 字段 |

---

## 2. 字段语义

存储于 `tb_rule_history` / `tb_rules_current`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `rollout_state` | `draft` \| `disabled` \| `dry_run` \| `canary` \| `full` | **运营真源**（R1+）；见 [rule-rollout.md](./rule-rollout.md) |
| `rule_status` | `draft` \| `active` \| `disabled` | **PoC 过渡**；R1 删除，映射见 rule-rollout §2.5 |
| `risk_score` | 0–100 | 命中强度；合并时取 top 意图组内 **max** |
| `intent_action` | `allow` \| `deny` \| `captcha` \| `review` | 规则命中后的**希望**动作（无 `rewrite` / `safe_reply`） |
| `enforce_mode` | `dry_run` \| `canary` \| `full` | **执行面导出**；deny/captcha 是否**真执行**（PoC 列名；R1 由 rollout 导出） |
| `canary_percent` | 1–100 或 null | **仅** `canary`（rollout 或 enforce）时必填 |

**解耦**：

- `intent_action`：规则定义「想做什么」
- `enforce_mode`：本次是否真拦 / 真 captcha
- `effective_action`：对外四值 `allow` \| `block` \| `captcha` \| `review`

### 2.1 intent_action 合并优先级（同层）

```text
deny(100) > captcha(50) > review(30) > allow(0)
```

同优先级多条 → `max(risk_score)` → `rule_revision` → `rule_id` 取 primary。

`allow` 意图命中可**短路**同层（白名单优先）。

### 2.2 enforce_mode × intent（deny / captcha）

| enforce_mode | 命中且 top 意图为 deny/captcha | 对外 effective_action |
|--------------|-------------------------------|------------------------|
| `dry_run` | 是 | `review` |
| `canary` | 是，且 `inCanaryBucket` | `block` 或 `captcha` |
| `canary` | 是，桶外 | `review` |
| `full` | 是 | `block` 或 `captcha` |

`intent_action=review` → 恒 `effective_action=review`（仍可将 prior 送云）。

### 2.3 Canary 分桶（管 / 云一致）

```text
key   = session_id（空则 "default"）
bucket = CRC32(key) % 100
in_canary = (bucket < canary_percent)
```

实现：`ActionMerge.inCanaryBucket` / gateway-agent `enforce.rs` / APISIX `virbius-guard.lua`。

---

## 3. 多规则合并（ActionMerge）

同一执行层内一次请求可命中多条规则：

```text
1. 收集命中集 H（非 allow 意图）
2. 若 ∃ allow 短路 → effective_action=allow
3. 按 §2.1 取 top 意图组 T
4. max_risk_score = max(risk_score) in T
5. 对 T 应用 enforce_mode → effective_action（§2.2）
6. primary = pick_primary(T)
```

**多条 canary**：在不存在 `full` 的前提下，**任一 canary 进桶即 effective**。

### 3.1 管侧与云侧串行

```text
请求
  → gateway ActionMerge
  → if effective_action ∈ {block, captcha}: 403/428 + 管侧审计，结束
  → if effective_action = review: prior_signals（内部）+ POST evaluate
  → cloud PolicyMerger(prior + 本地 signals) → EngineDecision
  → 无 cloud 命中时 Groovy L3 终判（脚本返回 action，见 groovy-l3-contract）
```

`prior_signals` 为 **gateway→engine 内部字段**，不出现在对外 API 响应。

### 3.2 HTTP 处置

| effective_action | HTTP |
|------------------|------|
| `allow` | 200，放行 |
| `review` | 200，放行（审计记录 review） |
| `block` | 403 |
| `captcha` | 428 |

---

## 4. 分层职责

| layer | runtime | 合并执行点 | 产物 |
|-------|---------|-----------|------|
| gateway | `list_match`, `cumulative`, `lua` | gateway-agent / APISIX | `{tenant}-access-lists.json` |
| cloud | `list_match`, `prompt`, `cumulative` | engine `PolicyMerger` | RuleCache |
| cloud | `groovy` | Groovy `decide(ctx)` + enforce 解析 | RuleCache |

---

## 5. Gateway 产物

`lists[]` / 规则块 **必须**携带：

```json
{
  "list_name": "deny_keyword",
  "rule_id": "rl_deny_keywords",
  "rule_revision": 3,
  "reason_code": "GW_CONTENT_KEYWORD_DENY",
  "risk_score": 100,
  "intent_action": "deny",
  "enforce_mode": "canary",
  "canary_percent": 5
}
```

---

## 6. 云侧 PolicyMerger

**输入**（内部）：`prior_signals[]` + Runner 产出 signals；RuleCache 补全 `intent_action` / `enforce_mode`。

**输出**（对外扁平）：

```json
{
  "effective_action": "review",
  "max_risk_score": 100,
  "rule_id": "cloud_prompt_l1",
  "rule_revision": 3,
  "reason_code": "PROMPT_INJECTION",
  "trace_id": "...",
  "degraded": false
}
```

Groovy L3：脚本返回 `[action: 'allow'|'block'|'captcha'|'review']`；`dry_run` 命中应返回 `review`（见 [groovy-l3-contract.md](./groovy-l3-contract.md)）。

---

## 7. Admin API

**目标（R1+）** — 见 [rule-rollout.md §7](./rule-rollout.md)：

- **规则内容** `POST .../rules`：不含 rollout；`dry_run|canary|full` 下改 body → 强制 `draft`
- **放量** `PATCH .../rules/{ruleId}/rollout`：`rollout_state` + `canary_percent`
- **租户策略** `GET|PUT .../tenants/{tenantId}/rollout-policy`：门禁阈值、阶梯（**不可 per-rule**）
- 便捷：`POST .../rollout/publish|rollback|disable|recover`

**PoC 现行**（R1 前）：

- **新建** `POST .../rules`：可设 `intent_action`；默认 `rule_status=draft` 或 active + `enforce_mode=dry_run`
- **启用** `PATCH .../rules/{ruleId}/status` → `active`
- **放量** `PATCH .../rules/{ruleId}/runtime`：`enforce_mode` + `canary_percent`（**禁止** dry_run→full，须走 canary）
- gateway 规则 enforce 变更 → `refreshArtifacts`；cloud → RuleCache 快照

---

## 8. 审计

| 终局 | 必填字段 |
|------|----------|
| gateway / cloud | `trace_id`, `tenant_id`, `layer`, `rule_id`, `rule_revision`, `reason_code`, **`effective_action`**, **`max_risk_score`**, `intercepted_at` |

推荐扩展（R2a+）：`rollout_state`, `canary_percent`, `in_canary_bucket`, `degraded`。

**投递**：各执行面 publish 至消息总线 → **AuditIngest** 入库；默认 **Redis Stream**（`virbius:audit:events`），可选 **Kafka**（`virbius.audit.events`）。PoC 仍写本地 jsonl 备档。详见 [rule-rollout.md §5.7](./rule-rollout.md)。

**不再写入** `would_block`。Schema：[audit-event.schema.json](./schemas/audit-event.schema.json)。

---

## 9. 运营 UI

- 规则表展示 `rollout_state`（或 PoC：`rule_status` / `enforce_mode`）/ `intent_action` / `risk_score`
- 「**策略上线**」侧栏：放量流程条、看板、门禁 evaluate（R2+）；见 [rule-rollout.md §8.3](./rule-rollout.md)
- Groovy 模板默认 `dry_run → review`

---

## 10. 验收用例（摘要）

| # | 条件 | 期望 |
|---|------|------|
| 1 | gateway `full` + `intent=deny` | 403，`effective_action=block`，无 evaluate |
| 2 | gateway `canary 5%` 进桶 + `intent=captcha` | 428，无 evaluate |
| 3 | gateway `canary 5%` 未进桶 | 200，`effective_action=review`，prior 送 evaluate |
| 4 | deny + captcha 同命中 | deny 优先（`max_risk_score` 取 deny 组） |
| 5 | cloud `dry_run` 注入命中 | `effective_action=review`，无 403 |
| 6 | cloud `full` 注入命中 | `effective_action=block` |
| 7 | 对外响应 | 无 `signals[]`、无 `would_block` |

---

## 11. 实现说明

合并逻辑统一在 **`ActionMerge`**（Java）/ **`enforce.rs`**（Rust）/ **`virbius-guard.lua`**（APISIX）。
