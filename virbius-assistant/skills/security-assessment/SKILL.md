---
name: assess-risk
description: VirbiusLLM 安全评估 — 查询 user/device 审计事件，进行风险分析并自动处置或创建复核工单。
trigger: "安全评估|风险分析|审计查询|assess.*risk|security assessment|check.*(user|device)|查一下.*风险|审计.*用户"
user-invocable: true
---

# VirbiusLLM Security Assessment

Follow this workflow when asked to evaluate a user or device's security risk.

## Step 1: Understand the Target

Extract from the user's question:

- **entity_type**: `user` or `device`
- **entity_value**: the user ID or device ID
- **time_start / time_end**: optional; default to last 7 days if not specified

If the user asks in a natural way (e.g. "帮我看看张三最近的风险情况"), determine the entity info from context.

## Step 2: Check Query Scope

Call `check_query_scope` with the time range:

```
check_query_scope(time_start, time_end)
```

- If `needs_confirmation === true`: show the user the message and **wait for their confirmation** before proceeding.
- If the user declines: stop and report back.

## Step 3: Query Audit Events

Call `query_audit_events` with the entity info and time range:

```
query_audit_events(tenant_id, entity_type, entity_value, time_start, time_end, limit=100)
```

Analyze the response:

| Indicator | Risk Level |
|---|---|
| `total_events >= 60` AND `max_risk >= 50` | High |
| Jailbreak / prompt_injection rules hit | Critical — immediate risk |
| `total_events < 60` AND `max_risk < 50` | Low (proceed to Step 4 for confirmation) |

## Step 4: Get Entity Profile

Call `get_entity_profile` for the same entity:

```
get_entity_profile(tenant_id, entity_type, entity_value)
```

Examine:
- Historical risk records
- Whether the entity is already blocked/blacklisted
- Past violation history

## Step 5: Assess Risk

Combine audit events + profile to determine the risk level:

**Risk Rules** (in priority order):

| Rule | Conclusion | Recommended Action |
|---|---|---|
| Jailbreak or prompt_injection rules hit | `risky` | `block` |
| `max_risk >= 80` AND `total_events >= 5` | `risky` | `block` |
| `max_risk >= 50` AND `total_events >= 20` | `uncertain` | `review` |
| Entity already blocked/blacklisted in profile | `risky` | `block` |
| None of the above | `safe` | `none` |

## Step 6: Take Action

| Conclusion | Action |
|---|---|
| `safe` | Skip — no action needed |
| `risky` + high confidence | Call `block_entity` (duration: 604800s = 7 days) or `rate_limit_entity` directly |
| `uncertain` | Call `create_review_ticket` with evidence for human review |
| Entity already blocked | Skip — report current status |

**Important**: For `risky` determinations, if the evidence is strong (e.g., jailbreak confirmed, high-risk score + high event count), take direct action. If uncertain, always create a review ticket.

## Step 7: Generate Report

Present a clear report to the user containing:

- Entity and time range assessed
- Total events found, max/avg risk score
- Key rule hits and patterns
- Entity profile summary (blocked history, past violations)
- Risk conclusion (`safe` / `risky` / `uncertain`) with confidence
- Action taken (blocked / rate-limited / ticket created / skipped)
- Ticket ID if created

## Step 8: Save Report (User Confirmation Required)

After presenting the report, **ask the user**:

> Whether to save the security assessment report to a local file?

- If **confirmed**: call `save_report_to_file` with the full report content.
  - `assessment_id`: generate as `asmt_YYYYMMDD_HHMMSS_<random6>`
  - `report_content`: the full report text you just presented
  - `conclusion`: `safe` / `risky` / `uncertain`
  - `ticket_id`: include if a ticket was created
- If **declined**: flow ends, do NOT call `save_report_to_file`.
