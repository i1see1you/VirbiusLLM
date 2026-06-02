# 规则放量与策略上线 — 统一设计（方案 A）

| 项目 | 说明 |
|------|------|
| 版本 | v1.3（2026-05-20） |
| 状态 | **设计定稿**（待实现） |
| 替代 | 运营层 `rule_status` + `enforce_mode` 双字段；API `/status` + `/runtime` |
| 关联 | [rule-level-enforce.md](./rule-level-enforce.md)、[audit-event.schema.json](./schemas/audit-event.schema.json)、[registry.openapi.yaml](./registry.openapi.yaml) |

---

## 1. 目标与范围

### 1.1 目标

为 **单条规则** 提供可运营、可自动化的策略上线闭环：

```text
配置(draft) → 上线观测(dry_run) → 灰度(canary) → 全量(full)
                    ↑______________________________|
                         回退 / 门禁 / 阶梯
```

并配套：

| 能力 | 说明 |
|------|------|
| **统一运营状态** | 单列 `rollout_state` + `canary_percent`，替代 status×enforce |
| **命中/拦截看板** | 按规则、按 rollout 阶段查看 review/block/captcha |
| **自动放量门禁** | 转移前指标校验，不达标拒绝或需 force |
| **canary 阶梯** | 5%→20%→50%→100%(full)，定时/条件推进，异常回退 |

### 1.2 非目标（本版）

- 请求 body / prompt 明文回放
- 跨租户全局调度、Flink 实时 OLAP（Phase 3）
- 无人值守 auto-full（须 `auto_mode=auto` 且可配置关闭）
- 修改 engine/gateway 合并算法（仍导出 `enforce_mode`）

### 1.3 不变量

- `intent_action` + `ActionMerge` → 对外四值 `effective_action` + `max_risk_score`
- HTTP：block→403，captcha→428，review/allow→200
- 对外 API **不返回** `signals[]`
- canary 分桶：`CRC32(session_id)%100 < canary_percent`

---

## 2. 核心数据模型

### 2.1 `rollout_state`（主字段）

| 值 | 进执行面 | 命中后（deny/captcha） | 可编辑 body |
|----|----------|------------------------|-------------|
| `draft` | 否 | — | 是 |
| `disabled` | 否 | — | 否 |
| `dry_run` | 是 | `review` | 否** |
| `canary` | 是 | 桶内 block/captcha；桶外 review | 否** |
| `full` | 是 | block/captcha | 否** |

\*\* **改 body 强制回 `draft`**：任意 `rollout_state ∈ {dry_run, canary, full}` 下若规则 body（含 intent/risk 等内容字段）变更，系统自动 `→ draft` 并移出执行面；须重新 publish 走完整上线流程。

**新建默认**：`rollout_state = draft`。

**上线（publish）**：`draft → dry_run`（一步；等同原 `active + dry_run`）。

### 2.2 `canary_percent`

| rollout_state | canary_percent |
|---------------|----------------|
| `canary` | **必填** 1–100 |
| 其他 | **必须 NULL** |

### 2.3 保留字段

| 字段 | 说明 |
|------|------|
| `intent_action` | allow / deny / captcha / review |
| `risk_score` | 0–100 |
| `runtime`, `layer`, `body`, `reason_code`, `rule_revision`, … | 规则内容 |

### 2.4 执行面导出（gateway / engine）

仅 `rollout_state ∈ {dry_run, canary, full}` 进入产物与 RuleCache。

```text
exportEnforce(rollout_state, canary_percent):
  dry_run  → enforce_mode=dry_run,  canary_percent=null
  canary   → enforce_mode=canary,   canary_percent=P
  full     → enforce_mode=full,      canary_percent=null
```

Agent / engine / Lua **只读 enforce 字段**；不知晓 `rollout_state`。

### 2.5 旧字段迁移映射

| rule_status | enforce_mode | canary_percent | → rollout_state |
|-------------|--------------|----------------|-----------------|
| draft | * | * | draft, null |
| disabled | * | * | disabled, null |
| active | dry_run | * | dry_run, null |
| active | canary | P | canary, P |
| active | full | * | full, null |

---

## 3. 状态机

### 3.1 转移图

```text
                         disable
              draft ──────────────► disabled
                │                      │
         publish│                      │ recover
                ▼                      ▼
             dry_run ◄──rollback── canary ◄──┐
                │    ▲              │  │      │
                │    │              │  │ step │
                │    └──────────────┘  ▼      │
                │                   full ────┘
                │                      │
                └──── disable ─────────┴── disable
```

### 3.2 合法转移表

| 从 | 到 | 说明 |
|----|-----|------|
| draft | dry_run | **publish / 上线** |
| draft | disabled | 作废 |
| dry_run | canary | 门禁通过；带 canary_percent |
| dry_run | disabled | 停用 |
| canary | canary | 仅变更 canary_percent（阶梯步进） |
| canary | full | 门禁 / 阶梯末步 |
| canary | dry_run | 手动或自动 **回退** |
| canary | disabled | 停用 |
| full | dry_run | 回退 |
| full | disabled | 停用 |
| disabled | draft | **恢复草稿**（不恢复历史 canary/full） |

**永久禁止**：draft→canary|full；**dry_run→full**（必须 dry_run→canary→…→full）；disabled→dry_run|canary|full；full→canary。

### 3.3 副作用（刷新执行面）

| 条件 | 动作 |
|------|------|
| 进入或离开 `{dry_run,canary,full}` | `refreshArtifacts` + `runtimeSnapshot` |
| draft↔draft、disabled 内 | 不刷新 |
| disabled→draft | 不刷新 |

### 3.4 与改 body 的关系

| 当前 rollout | 改规则内容（body 等） | 结果 |
|--------------|----------------------|------|
| `draft` | `POST .../rules` | 保持 `draft` |
| `disabled` | 不可编辑 | 409 |
| `dry_run` / `canary` / `full` | body 有变更 | **强制 `→ draft`**，移出执行面；写 rollout_event(trigger=body_change) |
| 任意 | `PATCH .../rollout` | 按转移表，不改 body |

改 body 后须重新 **publish → dry_run → canary → full**，无捷径。

---

## 4. 策略上线流程（运营 SOP）

### 4.1 阶段说明

| 阶段 | rollout_state | 运营动作 | 系统行为 |
|------|---------------|----------|----------|
| **① 配置** | draft | 写 body/intent/risk | 不进产物；零流量影响 |
| **② 上线观测** | dry_run | 点击「上线」→ publish | 进产物；命中→review；写 audit |
| **③ 看板评估** | dry_run | 看板查 24h+ review 量、样本、hit_rate | Gate evaluate→canary |
| **④ 灰度** | canary@5% | apply 或 ladder 自动步进 | 约 5% session 真拦 |
| **⑤ 放大** | canary@20→50% | 阶梯 + 门禁 | 逐步放大 |
| **⑥ 全量** | full | 末步或手动 full | 全量 block/captcha |
| **⑦ 异常** | dry_run / disabled | 回退或紧急停用 | 立即停止真拦 |

### 4.2 标准时间线（示例）

```text
T+0h    创建规则 (draft)，配置 deny + list_match
T+1h    publish → dry_run
T+0~48h 看板：review ≥100，review_rate 稳定，无 degraded 尖刺
T+48h   gate pass → canary 5%
T+60h   ladder → canary 20%
T+72h   ladder → canary 50%
T+84h   gate pass → full
```

实际时长由租户级 `tb_tenant_rollout_policy` 配置（**不可 per-rule 覆盖**）。

### 4.3 角色与模式

| auto_mode | 行为 |
|-----------|------|
| **manual** | 运营手动 PATCH rollout；看板仅展示指标与 evaluate 建议 |
| **assisted** | 门禁 evaluate；达标后 UI 提示「可升级」；**人工 confirm** apply |
| **auto** | 门禁 pass 后 Job 自动 PATCH；失败/回退写告警 |

默认：**assisted**。

---

## 5. 存储设计

### 5.1 规则表（`tb_rule_history` / `tb_rules_current`）

```sql
rollout_state   VARCHAR(16) NOT NULL DEFAULT 'draft',
canary_percent  INTEGER NULL,

-- 删除：rule_status, enforce_mode

CHECK (rollout_state IN ('draft','disabled','dry_run','canary','full')),
CHECK (
  (rollout_state = 'canary' AND canary_percent BETWEEN 1 AND 100)
  OR (rollout_state <> 'canary' AND canary_percent IS NULL)
)
```

每次 rollout 变更追加 **rule_history revision**（body 可不变）。

### 5.2 `tb_tenant_rollout_policy`（租户级，不可 per-rule）

门禁阈值、canary 阶梯、auto_mode **仅租户维度配置**；单条规则不可覆盖 ladder 或 dry_run→canary 门槛。

| 列 | 类型 | 默认 | 说明 |
|----|------|------|------|
| tenant_id | PK | | |
| auto_mode | enum | assisted | manual / assisted / auto |
| canary_ladder | JSON | [5,20,50,100] | 100=下一步 full；**租户统一** |
| min_dry_run_hours | int | 24 | dry_run→canary 最短观测 |
| min_review_count | int | 100 | dry_run→canary |
| max_review_rate | float | 0.05 | review/总请求上限 |
| max_review_spike_ratio | float | 2.0 | 相对前 7d 基线 |
| min_hours_per_step | int | 12 | 阶梯每步最短 |
| min_block_samples_per_step | int | 10 | canary 步进前桶内 block 样本 |
| allow_force | bool | true | 允许 force 绕过门禁 |
| rollback_block_spike_ratio | float | 3.0 | 自动回退：block 5min 超基线 σ |
| updated_at | timestamp | | |

### 5.2.1 `tb_rule_ladder_state`（per-rule 运行时）

| 列 | 类型 | 说明 |
|----|------|------|
| tenant_id, rule_id | PK | |
| ladder_status | enum | idle / running / paused |
| ladder_started_at | timestamp | |
| updated_at | timestamp | |

阶梯档位与门禁阈值读租户 policy；ladder 启停状态按规则记录。

### 5.3 `tb_rule_metrics_1h`（看板聚合）

| 列 | 说明 |
|----|------|
| tenant_id, rule_id, hour_bucket | 维度 |
| rollout_state, canary_percent | 该小时主快照（取 hour 内最后 revision） |
| cnt_review, cnt_block, cnt_captcha, cnt_allow | |
| cnt_total_requests | 分母 |
| cnt_degraded | fail-open 排除用 |

保留 90 天；Job 每 15min 增量。

### 5.4 `tb_rule_gate_log`

| 列 | 说明 |
|----|------|
| tenant_id, rule_id | |
| from_state, to_state | |
| from_percent, to_percent | |
| pass | bool |
| reasons_json | 失败原因数组 |
| metrics_snapshot_json | 评估时指标 |
| operator, comment | force 时必填 comment |
| created_at | |

### 5.5 `tb_rule_rollout_event`（时间线）

| 列 | 说明 |
|----|------|
| rule_id, rule_revision | |
| rollout_state, canary_percent | |
| trigger | manual / gate / ladder / rollback / force |
| operator | |
| effective_at | |

### 5.6 审计事件扩展（`VirbiusAuditEvent`）

在 [audit-event.schema.json](./schemas/audit-event.schema.json) 增加：

| 字段 | 必填 | 说明 |
|------|------|------|
| `rollout_state` | 推荐 | 决策时规则状态 |
| `canary_percent` | canary 时 | |
| `in_canary_bucket` | canary 时 | |
| `degraded` | 推荐 | engine/agent |

原有：`effective_action`, `max_risk_score`, `rule_id`, `rule_revision`, `trace_id`, …

**写入方**：gateway 终局拦截、engine evaluate、edge block（edge 可填 rollout 若已知）。

### 5.7 审计统一 ingest（消息总线）

各执行面（engine、gateway-agent、edge）**不直写** control DB；审计事件写入消息，由 **AuditIngest** 消费后入库 `tb_audit_events` 并供 metrics rollup。

| 项 | 约定 |
|----|------|
| **默认传输** | **Redis Stream**（`XADD virbius:audit:events`） |
| **可选传输** | **Kafka** topic `virbius.audit.events`（配置 `audit.ingest.backend=kafka`） |
| 消息体 | 符合 [audit-event.schema.json](./schemas/audit-event.schema.json) 的 JSON |
| 消费组 | `virbius-audit-ingest`；at-least-once；幂等键 `trace_id + rule_id + event_seq` |
| 本地降级 | 消费失败时 ingest 写 dead-letter；生产端可同步写 jsonl 备档 |

配置示例（control `application.yml`）：

```yaml
audit:
  ingest:
    backend: redis-stream   # redis-stream | kafka
    redis:
      stream-key: virbius:audit:events
    kafka:
      topic: virbius.audit.events
      bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
```

R2a 起 engine/gateway 默认走消息 publish；ingest 为 control 侧常驻消费者。

---

## 6. 服务架构

```text
                    ┌─────────────────────────────────────┐
                    │         virbius-control              │
                    │  RolloutService                      │
                    │  PromotionGateService                │
                    │  LadderJob (@Scheduled)              │
                    │  MetricsRollupJob                    │
                    │  RolloutDashboardService             │
                    └──────────┬──────────────────────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         ▼                     ▼                     ▼
  tb_tenant_rollout_     tb_audit_events         gateway JSON
  policy + rule ladder   ◄─ AuditIngest          RuleCache export
  gate_log, metrics        (Redis Stream / Kafka)
         │                     ▲
         ▼                     │ publish
   ops.html 左侧导航      engine / gateway / edge
   「策略上线」
```

Engine / gateway-agent：**不部署** Gate/Ladder；仅消费导出后的 enforce。

---

## 7. Admin API

### 7.1 规则内容（不变更 rollout）

```http
POST /api/v1/admin/tenants/{tenantId}/rules
```

- 新建：`rollout_state` 固定 `draft`。
- 更新：`disabled` 不可改；`dry_run|canary|full` 下 body 变更 → 强制 `draft`（§3.4）。

### 7.2 放量（核心）

```http
PATCH /api/v1/admin/tenants/{tenantId}/rules/{ruleId}/rollout
```

Request:

```json
{
  "rollout_state": "canary",
  "canary_percent": 5,
  "force": false,
  "comment": "optional, required if force=true"
}
```

Response: 完整 RuleRevision + 可选 `gate_result`。

Errors:

| code | 说明 |
|------|------|
| 409 INVALID_TRANSITION | 转移表不允许 |
| 409 GATE_FAILED | 门禁未过且 force=false |
| 409 INVALID_CANARY_PERCENT | canary 缺 percent 或非法 |

**便捷端点**

| 方法 | 路径 | 等价 |
|------|------|------|
| POST | `.../rollout/publish` | → dry_run |
| POST | `.../rollout/rollback` | → dry_run |
| POST | `.../rollout/disable` | → disabled |
| POST | `.../rollout/recover` | disabled → draft |

**废弃**：`PATCH .../status`、`PATCH .../runtime`（实现期可 308 转发）。

### 7.3 租户放量策略（不可 per-rule）

```http
GET  .../tenants/{tenantId}/rollout-policy
PUT  .../tenants/{tenantId}/rollout-policy
POST .../rules/{ruleId}/rollout/evaluate      # 阈值读租户 policy
POST .../rules/{ruleId}/rollout/ladder/start
POST .../rules/{ruleId}/rollout/ladder/pause
POST .../rules/{ruleId}/rollout/ladder/step   # 手动推进一步（assisted）
```

Evaluate 请求：

```json
{
  "target_state": "canary",
  "canary_percent": 5
}
```

Evaluate 响应：

```json
{
  "pass": false,
  "reasons": ["review_spike: review_24h=320 > threshold=180 (baseline_7d_daily_avg=90.0 × max_review_spike_ratio=2.0)"],
  "metrics": {
    "review_24h": 320,
    "total_requests_24h": 120000,
    "review_rate": 0.00267,
    "dry_run_hours": 26.5,
    "baseline_7d_daily_avg": 90.0,
    "baseline_days_with_data": 5,
    "max_review_spike_ratio": 2.0,
    "review_spike_ratio": 3.56,
    "g4_threshold": 180.0,
    "g4_pass": false,
    "g4_skipped": false
  },
  "suggested_next": null
}
```

### 7.4 看板

```http
GET .../rules/{ruleId}/metrics?from=&to=&granularity=1h
GET .../rules/{ruleId}/metrics/compare?baseline_hours=24
GET .../rules/{ruleId}/audit-samples?effective_action=review&limit=50
GET .../tenants/{tenantId}/traces/{traceId}
GET .../rules/{ruleId}/rollout/timeline
GET .../rules/{ruleId}/rollout/gates?limit=20
```

Metrics 响应示例：

```json
{
  "rule_id": "rl_deny_keywords",
  "rollout_state": "dry_run",
  "canary_percent": null,
  "series": [
    {
      "bucket": "2026-05-20T10:00:00Z",
      "review": 120,
      "block": 0,
      "captcha": 0,
      "allow": 9800,
      "total_requests": 9920,
      "hit_rate": 0.0121
    }
  ],
  "totals": { "review": 840, "block": 0, "captcha": 0 }
}
```

---

## 8. 命中/拦截看板（详细）

### 8.1 指标定义

| 指标 | 计算 |
|------|------|
| hit_rate | (review+block+captcha) / total_requests |
| review_rate | review / total_requests |
| block_rate | block / total_requests |
| canary_effective_block_rate | block / (total_requests × canary_percent/100)（近似） |
| degraded_rate | degraded / total_requests |

### 8.2 分阶段默认视图

| rollout_state | 主 KPI | 次要 |
|---------------|--------|------|
| dry_run | review 量、hit_rate | max_risk_score 分布、reason_code Top |
| canary | 桶内 block/captcha、桶外 review | 实际 vs 理论拦截比 |
| full | block/captcha、突增告警 | 按 scene 拆分 |

### 8.3 UI（ops.html）

**入口**：http://127.0.0.1:8080/ui（重定向 `/ops.html`）。

**全局布局**：

| 区域 | 内容 |
|------|------|
| **左侧导航**（可折叠） | 名单 · 累计 · 请求映射 · **规则**（云 / 管 / 端子菜单）· **策略上线** |
| **右上** | 租户 `tenant_id`（变更触发 `reloadAll()`） |
| **隐藏** | `bundleId` / `bundleVer`（PoC 固定 `poc-default` / `0.1.0`，供 API 与产物路径；不在 UI 展示） |
| **底部** | 操作日志 `#log` |

**日常放量 SOP**：按 `(tenant_id, rule_id)` 在「**策略上线**」页完成 `publish` / `evaluate` / `apply` / `rollback` 等；`RuleExecutionSync` 在状态变更后自动 `refreshArtifacts` + `runtimeSnapshot`，**无需**手点「刷新网关产物 / 发布 Engine」。  
**整包 Bundle 发布**（`POST .../bundles/.../publish`、`GET .../status`）为 **Legacy**，OpenAPI 标注 `[Legacy]`，**运营台不提供** Bundle 发布状态页。

**「策略上线」页**布局：

1. **规则区**：rule 选择器；badge 显示 `rollout_state` + `canary%` + `layer`
2. **流程条**：draft → dry_run → canary → full 高亮当前步
3. **KPI 卡片**：24h review/block/captcha/hit_rate
4. **图表**：按小时 stacked bar + rollout 变更竖线
5. **Gate 面板**：最近一次 evaluate（含 G4、`data_coverage`）；「评估升级」「应用」按钮
6. **样本表**：audit-samples；点击 trace 弹跨层链路
7. **租户策略**：租户 `rollout-policy` 编辑（admin；影响本租户全部规则）
8. **操作按钮**：publish / rollback / disable / recover（依状态显示）

### 8.4 分母 `total_requests`

PoC：agent 每 evaluate 写 counter（含 allow）；或 gateway 插件 hourly counter。

表：`tb_tenant_request_stats_1h(tenant_id, scene, hour_bucket, cnt)`。

无分母时看板仅展示绝对量，hit_rate 显示 N/A。

---

## 9. 自动放量门禁（详细）

### 9.1 GateService 接口

```text
GateResult evaluate(tenantId, ruleId, RolloutTransition transition)
GateResult evaluateApply(tenantId, ruleId, transition, force, operator, comment)
```

`RolloutTransition` = `{ from, to, to_canary_percent? }`。

### 9.2 默认门禁规则

#### dry_run → canary(P)

| # | 条件 |
|---|------|
| G1 | `dry_run_hours >= min_dry_run_hours` |
| G2 | `review_count_24h >= min_review_count` |
| G3 | `review_rate <= max_review_rate` |
| G4 | `review_count_24h <= max_review_spike_ratio × max(baseline_7d_daily_avg, 10)`；基线为 **dry_run**、**过去 7 个完整自然日（不含当前 24h）** 的日均 review；**baseline 有效日 < 3 则 skip** |
| G5 | `degraded_rate_24h < 0.01` |
| G6 | `canary_percent == ladder[0]`（若 ladder 启用） |

#### 9.2.1 G4 基线口径

| 项 | 定义 |
|----|------|
| `review_count_24h` | 与 G2 相同：`tb_rule_metrics_1h` 近 24h `cnt_review` 之和 |
| `baseline_7d_daily_avg` | `[T-8d, T-1d)` 内按自然日汇总 dry_run `cnt_review`，求和后 **÷ 7**（无数据日记 0） |
| `g4_threshold` | `max_review_spike_ratio × max(baseline_7d_daily_avg, 10)` |
| skip | `baseline_days_with_data < 3` → `g4_skipped=true`，不 fail |
| evaluate 字段 | `baseline_7d_daily_avg`、`review_spike_ratio`、`g4_threshold`、`g4_pass`、`g4_skipped` |

#### canary(P) → canary(P')

| # | 条件 |
|---|------|
| G7 | `hours_at_current_step >= min_hours_per_step` |
| G8 | `block_count_in_bucket_24h >= min_block_samples_per_step` |
| G9 | P' 为 ladder 中下一档 |

#### canary → full

| # | 条件 |
|---|------|
| G10 | 当前 percent 为租户 ladder 末档前一级 |
| G11 | G7、G8 满足 |

#### dry_run → full

**永久禁止**（API 409；无 force 绕过）。须 dry_run→canary→…→full。

#### 自动回退 → dry_run

| # | 条件 |
|---|------|
| R1 | `block_count_5m > rollback_threshold` |
| R2 | `block_rate_5m > rollback_block_spike_ratio × σ_7d` |

触发：LadderJob 或 MetricsJob；自动 PATCH + `ladder_status=paused` + 告警。

### 9.3 force 绕过

- `force=true` 且 `allow_force=true`
- `comment` 非空；写 gate_log.operator
- 仍写 rollout_event(trigger=force)

---

## 10. Canary 阶梯自动化（详细）

### 10.1 阶梯定义

租户级固定（不可 per-rule）：`canary_ladder = [5, 20, 50, 100]`（见 `tb_tenant_rollout_policy`）

| 当前 | 下一步 |
|------|--------|
| dry_run | canary@5 |
| canary@5 | canary@20 |
| canary@20 | canary@50 |
| canary@50 | full（100 表示 full，非 canary@100） |

### 10.2 LadderJob（每 15min）

```text
for each rule where ladder_status=running:
  cur = current rollout_state + canary_percent
  nxt = next(ladder, cur)
  if nxt is null: mark ladder complete; continue
  result = Gate.evaluate(cur → nxt)
  if result.pass:
    if auto_mode=auto: RolloutService.apply(nxt)
    if auto_mode=assisted: set pending_approval; notify UI
  if rollback_condition: RolloutService.apply(dry_run); pause ladder
```

### 10.3 启动 / 暂停

- **start**：仅 `rollout_state=dry_run`；gate 可选预检；`ladder_status=running`
- **pause**：不改动 rollout；停止 Job 推进
- **step**（assisted）：人工确认推进一步

### 10.4 与 canary 分桶一致性

改 percent 不重算 bucket；5%→20% 时更多 session 进桶。看板对比「理论新增拦截」与「实际 block 增量」。

---

## 11. 组件职责

| 组件 | 职责 |
|------|------|
| **RolloutService** | 转移校验、revision、刷新产物、写 rollout_event |
| **RolloutEnforceExport** | rollout → enforce 写入 ArtifactService / PublishService |
| **PromotionGateService** | evaluate / metrics 读取 |
| **MetricsRollupJob** | audit → metrics_1h |
| **LadderJob** | 阶梯 + 自动回退 |
| **RolloutDashboardService** | metrics API、samples、timeline |
| **AuditIngest** | Redis Stream（默认）/ Kafka → tb_audit_events |

---

## 12. 实施分期

| 阶段 | 交付 | 依赖 |
|------|------|------|
| **R1** | rollout_state schema；RolloutService；export enforce；PATCH rollout + 便捷端点；ops 状态 UI；废弃双 PATCH | — |
| **R2a** | audit 扩展；metrics_1h + rollup；看板 API + Tab | R1 + audit 写入 |
| **R2b** | rollout_policy；GateService；gate_log；evaluate/apply 集成 | R2a |
| **R2c** | LadderJob；assisted/auto；自动回退 | R2b |
| **R3a** | engine/gateway audit 补 rollout 字段；gateway-agent publish Stream | R2a |
| **R3b** | HTTP audit ingest；Kafka 真实现（可选）；ingest 幂等/DLQ | R3a |
| **R3c** | edge manifest.rules[]；compiler/control 产物 | R1 |
| **R3d** | virbius-core enforce + audit 队列 + HTTP 上报 + **采样** | R3b + R3c |
| **R3e** | 分母 counter；metrics hit_rate；evaluate `data_coverage` | R3d |
| **R3f** | ops trace / 数据健康；RC-14+ 验收 | R3e |
| **R3+** | Flink/CH；投诉信号接入门禁 | infra |

PoC 可交付最小闭环：**R1 + R2a + R2b（assisted only）**。  
端/管/云看板与门禁对 **edge 规则** 生效，需 **R3a–R3f**。

---

## 13. 迁移

1. 加列 `rollout_state`, `canary_percent`；按 §2.5 回填。
2. 双写期（可选 1 周）：API 同时接受旧 status/runtime，内部转 rollout。
3. 切换读 rollout；ops 新 UI。
4. 删 `rule_status`, `enforce_mode` 列。
5. 种子规则：`dry_run` 或 `full`（E2E 标注）。

---

## 14. 验收用例

| ID | 场景 | 期望 |
|----|------|------|
| RC-01 | 新建规则 | rollout_state=draft，不进产物 |
| RC-02 | publish | draft→dry_run，进产物；命中 review |
| RC-03 | dry_run 未达 min_review | evaluate fail；PATCH 409 |
| RC-04 | gate pass | canary 5%；桶内 block |
| RC-05 | ladder auto | 12h 后 20%→50%→full |
| RC-06 | block 尖刺 | 自动 rollback dry_run |
| RC-07 | disable | 不进产物；disabled 不可编辑 |
| RC-08 | recover | disabled→draft 可编辑 |
| RC-09 | 看板 | dry_run 段 review 曲线；变更竖线 |
| RC-10 | force | comment 记录；gate_log 可查 |
| RC-11 | dry_run 改 body | 强制 → draft，移出执行面 |
| RC-12 | dry_run→full | 409 永久禁止 |
| RC-13 | audit ingest | engine publish → Redis Stream → ingest 入库 |
| RC-14 | edge dry_run 命中 | SDK 上报 review；本地不 block；metrics review+1 |
| RC-15 | edge canary 5% | ~5% block，其余 review；`in_canary_bucket` 正确 |
| RC-16 | edge HTTP ingest | 批量 POST 入库；幂等重试不重复 |
| RC-17 | edge allow 采样 10% | allow 约 10% 上报；review/block 100%；rollup 分母可外推 |
| RC-18 | edge dry_run 24h | review≥min_review；evaluate pass |
| RC-19 | 跨层 trace | 同 trace_id 可查 edge/gateway/cloud 多行 audit |
| RC-20 | manifest rollout 变更 | SDK 热更新后 audit 带新 rollout 快照 |

---

## 15. 已定决策

| 项 | 决策 |
|----|------|
| 改 body | **强制回 `draft`**，须重走 publish 流程 |
| dry_run→full | **永久禁止**，必须 dry_run→canary→…→full |
| 门禁 / 阶梯 | **仅租户级** `tb_tenant_rollout_policy`；**不可 per-rule** |
| audit ingest | **消息总线**；默认 **Redis Stream**，可选 **Kafka** |
| 端 audit 通道 | **HTTP batch** → control ingest（不直连 Redis） |
| 端 audit 采样 | **`allow` 默认 10% 采样**；`review`/`block`/`captcha`/`degraded` **100%** |
| 端 ingest URL | **可配置**整 URL；路径固定 `POST /api/v1/internal/audit/events` |
| 运营字段名 | 保持 **`rollout_state`**（不改 `rule_state`） |

---

## 16. 长期方案（R3）：端管云 audit 闭环

### 16.1 目标

三端共用：**audit → ingest → tb_audit_events → metrics_1h → Gate / 看板**，运营 SOP 仍为 §4，不按 layer 分叉。

### 16.2 端侧上报流程

```text
Config Bus → edge-manifest.json（rules[] 含 rollout/enforce 快照）
    → virbius_init
    → virbius_scan（匹配 + enforce → effective_action）
    → 采样决策 → 本地队列
    → 批量 POST audit_ingest_url
    → control ingest → metrics → 策略上线页
```

### 16.3 端侧配置（SDK / Config Bus）

租户级或 App 级下发（**不可 per-rule**）：

| 键 | 类型 | 默认 | 说明 |
|----|------|------|------|
| `audit_ingest_url` | string | **无（必配）** | 完整 URL，如 `https://control/api/v1/internal/audit/events` |
| `audit_ingest_token` | string | — | Bearer token |
| `audit_sample_rate_allow` | float | **0.1** | 仅对 `effective_action=allow` 采样 |
| `audit_sample_rate_hit` | float | **1.0** | review/block/captcha 采样（默认全量） |
| `audit_flush_interval_ms` | int | 30000 | 定时 flush |
| `audit_queue_max` | int | 500 | 本地队列上限 |
| `canary_session_key` | enum | `device_id` | 分桶键：`device_id` / `install_id` |

可选：在 `tb_tenant_rollout_policy` 增列 `edge_audit_sample_rate_allow`（默认 0.1），与门禁策略同表维护；SDK 仍从 Config Bus 读生效值。

### 16.4 采样策略（性能）

```text
if effective_action == allow:
    report if random() < audit_sample_rate_allow   # 默认 10%
else:
    report always                                 # review/block/captcha/degraded 100%
```

rollup 时：对带 `sampled_allow=true` 的事件，`cnt_allow` 可乘 `1/audit_sample_rate_allow` 估算分母（ingest 或 MetricsRollupJob 实现）。

### 16.5 HTTP Ingest API

```http
POST /api/v1/internal/audit/events
Authorization: Bearer {audit_ingest_token}
Content-Type: application/json

{ "events": [ /* VirbiusAuditEvent[] */ ] }
```

单条事件 schema 见 [audit-event.schema.json](./schemas/audit-event.schema.json)。  
Redis Stream 仍用于 engine/gateway：`XADD virbius:audit:events * payload '<json>'`。

### 16.6 管/云/端 audit 通道（R3a–R3d）

| layer | 通道 | 状态 |
|-------|------|------|
| cloud | Redis Stream | ✅ R3a：`AuditWriter` 写 `rollout_state`、`canary_percent`、`in_canary_bucket` |
| gateway | Redis Stream | ✅ R3a：`gateway-agent` audit 模块；allow 也写 |
| edge | HTTP batch | ✅ R3d：`virbius-core` enforce + 采样 + 批量 POST ingest |

**可选增强**（见 §17）：Kafka Producer/Consumer、Stream `XREADGROUP` + DLQ、G5 `degraded` 指标、SDK C API（`virbius_init_config`）。

### 16.7 evaluate 数据充足性

`POST .../rollout/evaluate` 响应增加：

```json
{
  "data_coverage": {
    "audit_events_24h": 120,
    "layer": "edge",
    "ingest_lag_p95_minutes": 2.5,
    "sufficient": true
  }
}
```

`sufficient=false` 时 UI 提示「SDK 上报异常」，而非仅显示门禁失败。

---

## 17. 代码改动清单（R3，先设计后实现）

> **R3 主路径已实现**（2026-05-20）；下列 **可选/增强** 项仍待补：Kafka ingest/producer、XREADGROUP+DLQ、G5 degraded、SDK C API 扩展。

### 17.1 R3a — engine / gateway audit

| 模块 | 文件 | 改动 |
|------|------|------|
| virbius-engine | `audit/AuditWriter.java` | 写 `rollout_state`、`canary_percent`、`in_canary_bucket` |
| virbius-engine | `cache/RuleEntry.java` | 增加 rollout 快照字段 |
| virbius-engine | `persist/RuleCachePersistence.java` | 持久化 rollout 相关字段 |
| virbius-engine | `eval/EvaluateOrchestrator.java` | 传 RuleCache rollout 给 AuditWriter |
| virbius-policy | `audit/AuditEventPublisher.java` | Kafka 从 stub → 真 Producer（**可选，未做**） |
| virbius-gateway-agent | **新建** `src/audit.rs` | ✅ |
| virbius-gateway-agent | `src/main.rs` | ✅ |
| virbius-gateway-agent | `Cargo.toml` | ✅ |

### 17.2 R3b — control ingest

| 模块 | 文件 | 改动 |
|------|------|------|
| virbius-control | **新建** `audit/AuditEventIngestor.java` | ✅ |
| virbius-control | `audit/AuditIngestService.java` | ✅（无 DLQ） |
| virbius-control | **新建** `api/InternalAuditController.java` | ✅ |
| virbius-control | **新建** `dto/request/AuditEventsBatchRequest.java` | ✅ |
| virbius-control | **新建** `config/AuditIngestSecurityConfig.java` | token 在 Controller 内联 |
| virbius-control | `resources/application.yml` | ✅ |
| virbius-control | **新建** `audit/KafkaAuditIngestService.java` | **可选，未做** |

### 17.3 R3c — edge 产物

| 模块 | 文件 | 改动 |
|------|------|------|
| virbius-compiler | `CompilerCli.java` | ✅ `--target=edge|gateway|cloud|all` |
| virbius-compiler | **新建** `EdgeManifestEmitter.java` | ✅ manifest.rules[] + enforce 导出 |
| virbius-control | `service/ArtifactService.java` | ✅ |
| virbius-control | `service/RuleExecutionSync.java` | ✅ |
| docs | `schemas/edge-manifest.schema.json` | ✅ rules[] + sdk_config |

### 17.4 R3d — virbius-core（端 SDK）

| 模块 | 文件 | 改动 |
|------|------|------|
| virbius-core | **新建** `src/manifest.rs` | 解析 rules[] + SDK 配置（ingest_url、sample_rate） |
| virbius-core | **新建** `src/enforce.rs` | 与 gateway 同算法 |
| virbius-core | **新建** `src/matcher.rs` | manifest 驱动匹配 |
| virbius-core | **新建** `src/audit.rs` | 事件构造 + **采样** + 本地队列 |
| virbius-core | **新建** `src/upload.rs` | HTTP batch flush 到 `audit_ingest_url` |
| virbius-core | `src/lib.rs` | scan 流程接入 enforce + audit |
| virbius-core | `include/virbius.h` | `virbius_init_config` 或扩展 init；`install_id` |
| virbius-core | `Cargo.toml` | reqwest/ureq、rusqlite 等 |

**采样实现要点（`audit.rs`）**

- 读 `audit_sample_rate_allow`（默认 `0.1`）、`audit_sample_rate_hit`（默认 `1.0`）
- allow 路径：`thread_rng().gen::<f64>() < rate`
- 事件可选字段：`sampled_allow: true` 供 rollup 外推

### 17.5 R3e — metrics / 门禁

| 模块 | 文件 | 改动 |
|------|------|------|
| virbius-control | `resources/db/schema.sql` | `tb_tenant_request_stats_1h`；metrics 可选 `layer` |
| virbius-control | `job/MetricsRollupJob.java` | allow 采样外推；分母 rollup |
| virbius-control | `service/PromotionGateService.java` | ✅ `data_coverage` + **G4 基线** |
| virbius-control | `service/RolloutDashboardService.java` | hit_rate、ingest 健康 |
| virbius-control | `admin/RolloutAdminController.java` | `GET .../traces/{traceId}` |
| virbius-control | `resources/db/seed.sql` | 可选 `edge_audit_sample_rate_allow` 默认 0.1 |

### 17.6 R3f — ops UI

| 模块 | 文件 | 改动 |
|------|------|------|
| virbius-control | `resources/static/ops.html` | ✅ 左侧折叠导航；策略上线看板；layer badge；G4 + `data_coverage`；trace 弹窗；租户右上；**无** Bundle 发布状态页 / 手动刷新工具栏 |

### 17.7 文档 / OpenAPI

| 文件 | 改动 |
|------|------|
| `docs/openspec/rule-rollout.md` | 本节 §16–§17 |
| `docs/openspec/schemas/audit-event.schema.json` | 可选 `sampled_allow` |
| `docs/openspec/registry.openapi.yaml` | internal audit API |
| `docs/openspec/MVP-OPENSPEC.md` | 端 HTTP ingest + 采样 |

### 17.8 实施顺序

```text
R3a ──► R3b ──► R3d
R3c ──────┘
R3a+R3b ──► R3e ──► R3f
```

R3a 与 R3c 可并行；**R3d 依赖 R3b（HTTP ingest）+ R3c（manifest）**。

---

## 18. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-05-20 | 方案 A + 看板 + 门禁 + 阶梯 + 策略上线 SOP 合一 |
| 1.2 | 2026-05-20 | OpenSpec / DESIGN / registry.openapi / audit schema 统一口径 |
| 1.3 | 2026-05-20 | **R3 长期方案**：端 HTTP ingest、allow 10% 采样、§17 代码改动清单、RC-14+ |
| 1.4 | 2026-05-20 | **G4 门禁**、compiler `EdgeManifestEmitter`、`--target=edge`、OpenAPI/schema 同步 |
| 1.5 | 2026-05-20 | **§8.3** ops 左侧导航与策略上线 SOP；§16.6 与 §17 状态对齐；Bundle 发布标 Legacy |
