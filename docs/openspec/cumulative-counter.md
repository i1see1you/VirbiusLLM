# 累计规则与 CounterStore（OpenSpec 草案）

与 [DESIGN.md §8.5.0.1](../DESIGN.md#8501-累计规则counterstore) 对齐。

| 项目 | 说明 |
|------|------|
| 状态 | **PoC 已实现**（gateway ingest + engine read 共用 Redis；未配置 `VIRBIUS_REDIS_URL` 时 gateway 回退进程内内存） |
| 存储 | 定义 → `tb_cumulative`；数值 → **Redis** |
| 规则 | `runtime=cumulative`；body 必填 `cumulative_name` + **`condition`**；可选 `value_source` |
| 对称能力 | 名单 → [list-match.md](./list-match.md)；value 解析 → [value-resolution.md](./value-resolution.md) |

---

## 1. 架构原则

| 原则 | 说明 |
|------|------|
| 定义与数值分离 | 窗口、阈值、reason 在 **DB**；计数在 **Redis** |
| 平台代读写 | **CounterStore** Ingest/Read；Groovy/Lua **不**直连 Redis |
| 管云共用 | 无 `layer_scope`；同一 `cumulative_name` 同一 Redis 键空间 |
| Ingest 单写 | **仅管侧**每条请求写入；云侧只 Read |
| 上下文注入 | `ctx.cumulative(name)` 供 Groovy；管侧产 `prior_signal` |
| value | 默认 `dimension` 解析；规则可选 `value_source` → [value-resolution.md](./value-resolution.md) |

```text
value = resolveValue(rule, cumDef, request)
请求 → CounterStore.ingest(value, …)（active 累计）
     → getCumulate(value, cumulativeName) → 比较 threshold → Signal / 403
     →（可选）云 Evaluate → ctx.cumulative → Groovy L3
```

---

## 2. 数据模型：`tb_cumulative`

主键：`(tenant_id, cumulative_name)`。`cumulative_name` 租户内唯一。

| 字段 | 必填 | 说明 |
|------|------|------|
| `tenant_id` | ✅ | |
| `cumulative_name` | ✅ | 唯一标识 |
| `description` | | 说明 |
| `dimension` | ✅ | `user_id` \| `device_id` \| `ip` \| `session_id` \| `var:{logical}` |
| `window_kind` | ✅ | `rolling` \| `calendar_day`（**无** `calendar_week`） |
| `window_minutes` | △ | rolling 与 `window_hours` **互斥二选一** |
| `window_hours` | △ | rolling；`1 ≤ W ≤ 168`（`W` 最大 10080 分钟） |
| `timezone` | △ | `calendar_day` **必填**（如 `Asia/Shanghai`） |
| `priority` | | 多累计并存时的顺序 |
| `status` | ✅ | `active` \| `disabled` |

**判定**（`condition.threshold` / `compare_op`）与 **处置**（`reason_code`、`risk_score`、`intent_action`）在 **累计规则** 上配置，见 §3。

**「自然周」**：不单独建模；用 **7×`calendar_day`** 或一条 **`rolling` + `window_hours: 168`**。

---

## 3. 规则（`tb_rule_history`）

### 3.1 最简

```yaml
rule_id: rl_user_req_1h
runtime: cumulative
layer: gateway
reason_code: GW_USER_RATE_1H
risk_score: 80
intent_action: captcha
body:
  cumulative_name: user_req_1h   # 必填
  condition:                     # 必填
    compare_op: gte
    threshold: 120
```

平台：`count = getCumulate(value, cumulativeName)` → `RuleConditionEvaluator.evaluate(count, condition)` → Signal（reason/risk/intent 来自规则行）。

### 3.2 指定 value 来源（可选）

```yaml
body:
  cumulative_name: req_per_app_1h
  condition:
    compare_op: gte
    threshold: 120
  value_source:
    kind: var
    ref: app_id
```

未写 `value_source` 时：`value = resolve(cumDef.dimension, request)`，再 `getCumulate(value, cumulativeName)`。详见 [value-resolution.md](./value-resolution.md)。

窗口与 dimension **在 `tb_cumulative`**；**condition 与处置在规则**。

同一 `cumulative_name` 可绑多条规则（如 gateway 拦截 + cloud 审计）。若规则间 **`value_source` 不同**，Redis 按不同 `value` 分流计数（见 §6.1 Key）。

---

## 4. `window_hours` 与 `window_minutes`

二者均描述 **rolling** 窗口长度，最终换算为读取时 SUM 的 **桶个数**：

```text
W（分钟）= window_minutes  或  window_hours × 60
```

| 配置示例 | W | 说明 |
|----------|---|------|
| `window_minutes: 5` | 5 | 最近 5 分钟 |
| `window_minutes: 60` | 60 | 最近 1 小时 |
| `window_hours: 24` | 1440 | 最近 24 小时 |
| `window_hours: 168` | 10080 | 最近 7 天 |

`calendar_day` **不使用** 上述字段；按租户时区当日 0 点至今。

---

## 5. 桶粒度 `G`（平台自动推导，不落库）

| 条件 | `G`（分钟） |
|------|-------------|
| `rolling` 且 `W < 1440` | **1** |
| `rolling` 且 `W ≥ 1440` | **10** |
| `calendar_day` | **10** |

写入：`HINCRBY key slot 1`，`slot = floor(epoch_sec / (G*60))`。

读取：`bucket_count = ceil(W / G)`（rolling）或当日 slot 区间（calendar_day）；`bucket_count == 1` 时 `HGET`，否则 **Lua SUM**。

| 窗口 | G | 约 bucket_count |
|------|---|-----------------|
| 5m | 1 | 5 |
| 1h | 1 | 60 |
| 24h | 10 | 144 |
| 7d rolling | 10 | 1008 |
| 自然日 | 10 | ≤144 |

**禁止**同一 Redis key 混用不同 `G`；跨 24h 改窗口应 **新建 `cumulative_name`**。

---

## 6. Redis

### 6.1 Key

```text
virbius:cum:{tenant_id}:{cumulative_name}:{value}
```

- **`value`**：经 [value-resolution.md](./value-resolution.md) 解析后的字符串（trim，**不做 hash**）；**不含** `dimension` 段，以便规则级 `value_source` 覆盖。
- `tb_cumulative.dimension`：仅作**默认** value 解析，不强制出现在 key 中。
- 进 Key 前：长度 ≤ **256**；非法字符按 **URL 编码**（审计保留原文）。
- `ip` 建议规范化为标准 IP 字符串。

Type：`HASH`，`field=slot`，`value=count`。

### 6.2 TTL

```text
ttl_minutes = W_eff + BUFFER_MIN
BUFFER_MIN = 120（建议）
```

| `window_kind` | `W_eff` |
|---------------|---------|
| `rolling` | `W` |
| `calendar_day` | `1440` |

每次 Ingest 刷新 `EXPIRE`（建议与 `HINCRBY` 同事务/Lua）。

### 6.3 空值 / null

| 条件 | 行为 |
|------|------|
| 维度 `null`、空串、仅空白 | **不 Ingest、不 Read** |
| 超长 | 同上 |

审计事件建议：

```text
event: cumulative_skipped
reason: empty | null | too_long
fields: tenant_id, cumulative_name, dimension, trace_id
```

**PoC 实现（gateway-agent `cumulative.rs`）**：读取 `VIRBIUS_REDIS_URL` 连接 Redis；每条命中累计规则时 `HINCRBY` + `EXPIRE`（ingest）再 `HGET`/`HMGET`（read），键格式与 §6.1 一致，与 Java `CounterStore` 对齐。未配置 Redis 时回退进程内 `HashMap`（仅本地/单测）。

---

## 7. CounterStore API（平台）

### 7.1 `ingest(tenant, rule?, MatchContext)`

- 对租户所有 **`status=active`** 的 `tb_cumulative`（可按 scene 过滤，P1）执行 Ingest。
- 每条累计按 **effective value**（定义 `dimension` 或绑定规则的 `value_source`）写桶。
- 无有效 value → 跳过（见 §6.3）。
- Redis 失败 → `degraded`；策略见 tenant `counter_fail_policy`（`fail_open` / `fail_close`）。

### 7.2 `read(tenant, cumulative_name, rule?, MatchContext)` → `CumulativeSnapshot`

等价于 `getCumulate(value, cumulativeName)`，`value` 由 resolveValue 得到。

| 字段 | 说明 |
|------|------|
| `count` | SUM 结果 |
| `value` | 本次计数键使用的 value |
| `value_source_kind` | 生效来源 |
| `threshold` / `exceeded` | 判定 |
| `granularity_min` | 1 或 10 |
| `window_kind` / `W_minutes` | |
| `start_slot` / `end_slot` / `bucket_count` | 审计 |
| `degraded` | 读失败 |

### 7.3 `ctx.cumulative(name)`（Groovy）

与 `CumulativeSnapshot` 只读字段一致；**不**暴露 Redis key。

### 7.4 执行顺序（建议）

绑定范围（`bind_scope`）见 **[bind-scope.md](./bind-scope.md)**：ingest / 判定仅对匹配 Global / Service / Route 的请求生效。

```text
allow 名单 → ingest(全部 active 累计) → deny 名单
→ cumulative 规则 read/判定 → 云 Evaluate（prior_signals + 预取 cumulative）
```

推荐：**先 Ingest 再 Read**（本次请求计入当前窗口）。

---

## 8. Lua 脚本

### 8.1 `CUM_INGEST`

| KEYS | ARGV |
|------|------|
| `[1]` redis key | `slot`, `delta`（通常 1）, `ttl_sec` |

```lua
local c = redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return c
```

### 8.2 `CUM_READ`

| KEYS | ARGV |
|------|------|
| `[1]` redis key | `start_slot`, `end_slot` |

对 `start_slot..end_slot` 的 field 求和返回 `count`（缺失 field 视为 0）。

### 8.3 高并发

- 每请求 K 个累计：**Pipeline** K 次 `CUM_INGEST` / `CUM_READ`（1 RTT）。
- Redis **Cluster**，与网关 **同 AZ**。
- 每租户 active cumulative 上限建议 **≤32**。
- gateway-agent **集中** Redis 连接（避免 APISIX worker × 连接风暴）。

---

## 9. Signal metadata（审计）

```json
{
  "cumulative_name": "user_req_24h",
  "dimension": "user_id",
  "value": "u-123",
  "count": 121,
  "threshold": 120,
  "granularity_min": 10,
  "window_kind": "rolling",
  "W_minutes": 1440,
  "start_slot": 29238400,
  "end_slot": 29238543,
  "bucket_count": 144,
  "exceeded": true
}
```

`value` 可按租户策略脱敏。

---

## 10. 发布校验

| # | 规则 |
|---|------|
| 1 | `window_kind ∈ {rolling, calendar_day}` |
| 2 | rolling：`window_minutes` XOR `window_hours`；`1 ≤ W ≤ 10080` |
| 3 | `calendar_day` 必须 `timezone` |
| 4 | `runtime=cumulative` → `body.cumulative_name` 存在且 active |
| 5 | 改窗口导致 `G` 变化 → 新 `cumulative_name` |
| 6 | 租户 active cumulative ≤ N（建议 32） |
| 7 | `value_source` → [value-resolution.md](./value-resolution.md) |

---

## 11. 配置示例

| cumulative_name | window_kind | window | timezone | G | bucket_count≈ |
|-----------------|-------------|--------|----------|---|---------------|
| `req_5m` | rolling | `window_minutes: 5` | — | 1 | 5 |
| `req_1h` | rolling | `window_minutes: 60` | — | 1 | 60 |
| `req_24h` | rolling | `window_hours: 24` | — | 10 | 144 |
| `req_7d` | rolling | `window_hours: 168` | — | 10 | 1008 |
| `req_today` | calendar_day | — | Asia/Shanghai | 10 | ≤144 |

---

## 12. 非目标（PoC）

- `calendar_week` 与 `week_start`
- Redis value **hash**
- Groovy/规则脚本直连 Redis
- 云侧 Ingest（双写）
- 业务库存分钟级计数

---

## 13. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v0.1 | 2026-05-20 | 初稿：tb_cumulative、CounterStore、自适应 1/10 分钟桶 |
| v0.2 | 2026-05-20 | 去掉 calendar_week；Key 用明文 value；空值跳过+审计 |
| v0.3 | 2026-05-20 | 规则可选 value_source；Key 去掉 dimension 段；value-resolution |
