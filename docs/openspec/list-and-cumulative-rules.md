# 名单规则与累计规则 — 最新设计（合并定稿）

| 项目 | 说明 |
|------|------|
| 状态 | 设计冻结，**PoC 代码尚未实现** |
| 版本 | v1.0（2026-05-20） |
| 关联 | [value-resolution.md](./value-resolution.md)、[list-match.md](./list-match.md)、[cumulative-counter.md](./cumulative-counter.md)、[rule-level-enforce.md](./rule-level-enforce.md)、[DESIGN.md §8.5.0.1–2](../DESIGN.md) |

---

## 1. 设计目标

| 目标 | 做法 |
|------|------|
| 配置简单 | 先建**定义**（名单 / 累计），再建**规则**只引用 `list_name` / `cumulative_name` |
| 执行统一 | 平台 **`resolveValue` → Store 调用 → 注入 ctx → 规则引擎 / Groovy** |
| 扩展性 | 规则可选 **`value_source`**（通用）；不配则用定义上的 **`dimension`** |
| 与现架构一致 | Registry 真源；管侧 fail-fast；云 Groovy L3；**不**在脚本内直连 Redis/扫表 |

---

## 2. 总体架构

```text
                    ┌─────────────────────────────────────┐
                    │  virbius-control（Registry 真源）      │
                    │  tb_access_list_meta + entry        │
                    │  tb_cumulative                      │
                    │  tb_rule_history（list_match /      │
                    │    cumulative + 可选 value_source） │
                    └──────────────┬──────────────────────┘
                                   │ 发布 → 快照 / RuleCache
         ┌─────────────────────────┼─────────────────────────┐
         ▼                         ▼                         ▼
   ListSnapshot              Redis（累计数值）          Engine RuleCache
   (gateway JSON)            分钟/十分钟桶
         │                         │
         ▼                         ▼
   ListStore.match          CounterStore.ingest/read
         │                         │
         └────────────┬────────────┘
                      ▼
              resolveValue(rule, def, request)
                      ▼
              ctx.list() / ctx.cumulative()
                      ▼
              Signal → 合并链 → Groovy L3
```

### 2.1 概念接口（平台实现，非业务脚本）

```text
String value = ValueResolver.resolve(rule, definition, request);

boolean matched = ListStore.match(value, listName);
int count       = CounterStore.getCumulate(value, cumulativeName);  // 内含 read；ingest 见 §5
```

---

## 3. 通用：value 解析（`value_source`）

**完整规范**：[value-resolution.md](./value-resolution.md)。

### 3.1 优先级

```text
if rule.body.value_source 存在:
    value = resolve(value_source, request)
else:
    value = resolve(definition.dimension, request)
```

### 3.2 `value_source`（规则 body 可选，**名单与累计共用**）

```yaml
value_source:
  kind: request_field | var | header | query | content | literal
  ref: <string>       # 视 kind
  value: <string>     # 仅 literal
```

| `kind` | 说明 |
|--------|------|
| （省略） | 使用定义上的 `dimension` |
| `request_field` | `user_id` / `device_id` / `session_id` / `client_ip` |
| `var` | 逻辑变量（`context_bindings`） |
| `header` / `query` | 扩展字段 |
| `content` | 正文（关键词名单） |
| `literal` | 常量（如全站 `_global_`，慎用） |

### 3.3 无效 value

`null`、空串、超长（>256）→ **不执行** match/ingest/read；审计 `list_skipped` / `cumulative_skipped`。

### 3.4 两类「value」不要混淆

| 名称 | 配置位置 | 含义 |
|------|----------|------|
| **请求 value** | 不配置，运行时解析 | 本次请求的 `user_id`、正文等 |
| **名单 entries** | 名单条目表 | 静态黑/白名单集合 |
| **累计 threshold** | `tb_cumulative` | 与 count 比较的阈值 |

---

## 4. 名单规则（ListStore）

### 4.1 数据模型

**`tb_access_list_meta`** — `(tenant_id, list_name)` PK

| 字段 | 说明 |
|------|------|
| `list_name` | 租户内唯一 |
| `polarity` | `deny` \| `allow` |
| `dimension` | 默认 value 来源：`user_id` \| `device_id` \| `ip` \| `session_id` \| `keyword` \| `var:{name}` |
| `priority` / `status` / `version` | 顺序、启停、发布版本 |

**`tb_access_list_entry`** — `(tenant_id, list_name, value)` PK

| 字段 | 说明 |
|------|------|
| `value` | 静态条目（如 `u-evil`、`暴恐`） |

### 4.2 规则

```yaml
rule_id: rl_block_users
runtime: list_match
layer: gateway              # 或 cloud：在本层 match 并产 Signal / 403
reason_code: GW_USER_DENY
risk_score: 100
rule_status: active
body:
  list_name: blocked_users  # 必填
  # value_source:          # 可选，见 §3
  #   kind: var
  #   ref: app_id
```

| 配置项 | 位置 |
|--------|------|
| 黑/白名单条目 | **entries** |
| 默认从哪取请求 value | **`dimension`** |
| 覆盖 value 来源 | 规则 **`value_source`** |
| 命中后 reason/risk | 规则行（或绑定元数据，实现二选一） |

### 4.3 运行时

- **ListStore.match(value, listName)**：在发布快照/条目集中匹配。
- **keyword**：`value` 为正文，条目为子串（PoC）。
- **注入**：`ctx.list(name).matched()`、`value()`、`matched_entry()` 等。
- **产物**：gateway `lists[]`（含 `list_name`、`entries`、`rule_id`、`reason_code`）；**删除** `sync-rules` 固定 12 `rule_id` 投影。
- **Lua/agent**：调用 ListStore 等价逻辑；reason/rule_id **来自配置**，不写死。

### 4.4 执行顺序（建议）

```text
1. active allow 名单（priority）
2. CounterStore.ingest（全部 active 累计）
3. active deny 名单
4. cumulative 规则判定
5. 云 Evaluate（prior_signals + ctx 预取）
```

---

## 5. 累计规则（CounterStore）

### 5.1 数据模型 — `tb_cumulative`

主键：`(tenant_id, cumulative_name)`。

| 字段 | 说明 |
|------|------|
| `cumulative_name` | 租户内唯一 |
| `dimension` | 默认 value 来源（同名单语义） |
| `window_kind` | `rolling` \| `calendar_day`（**无** `calendar_week`） |
| `window_minutes` | rolling，与 `window_hours` **互斥二选一** |
| `window_hours` | rolling；`1 ≤ W ≤ 168`（最多 10080 分钟） |
| `timezone` | `calendar_day` **必填** |
| `threshold` / `compare_op` | 默认 `gte` |
| `on_exceed_suggest` / `on_exceed_risk_score` / `on_exceed_reason_code` | 超限时 Signal |
| `priority` / `status` | |

**自然周**：用 `rolling` + `window_hours: 168`，或多个 `calendar_day` 规则/Groovy 组合；**不**单独建 `calendar_week`。

**无 `layer_scope`**：同一累计定义管云共用；规则 `layer` 只表示在哪一层判定。

### 5.2 规则

```yaml
rule_id: rl_user_req_1h
runtime: cumulative
layer: gateway
body:
  cumulative_name: user_req_1h   # 必填
  # value_source:               # 可选，见 §3
```

窗口、阈值、reason **仅在 `tb_cumulative`**，不在规则 body 重复。

### 5.3 Redis

**Key**（无 `dimension` 段，支持规则级 `value_source` 覆盖）：

```text
virbius:cum:{tenant_id}:{cumulative_name}:{value}
```

- `value`：明文，trim，≤256；空值跳过。
- Type：`HASH`，`field=slot`，`value=count`。

**桶粒度 `G`（平台推导，不落库）**

| 条件 | G |
|------|---|
| rolling 且 W < 1440 分钟 | 1 分钟 |
| rolling 且 W ≥ 1440 分钟 | 10 分钟 |
| calendar_day | 10 分钟 |

**写入**：`HINCRBY` 当前 `slot`；**读取**：`HGET`（单桶）或 Lua **SUM** 最近 `bucket_count` 个 field。

**TTL**：`W_eff + 120` 分钟（`calendar_day` 的 `W_eff=1440`）。

### 5.4 运行时

| 环节 | 位置 |
|------|------|
| **Ingest** | **仅管侧**（每条请求，对 active 累计 + effective value） |
| **Read / 判定** | 管、云均可 |
| **接口** | `getCumulate(value, cumulativeName)` → `ctx.cumulative().count()` / `exceeded()` |
| **顺序** | 推荐 **先 Ingest 再 Read**（本次计入窗口） |

---

## 6. 规则引擎与 Groovy

| 阶段 | 行为 |
|------|------|
| 平台 | 按规则/绑定预取 `matchList` / `getCumulate`，写入 **ExecutionContext** |
| `list_match` / `cumulative` 规则 | 根据快照自动产 Signal（超阈/命中） |
| **Groovy L3** | 只读 `ctx.list()`、`ctx.cumulative()`、`ctx.signals()`；**不**直连 Redis/DB |
| 合并 | 多 Signal；primary 建议 **max risk**（与 DESIGN 方向一致） |

---

## 7. 配置示例（端到端）

```yaml
# ---------- 名单 ----------
# 定义
list_name: blocked_users
polarity: deny
dimension: user_id
entries: [ "u-evil", "u-001" ]

# 规则
rule_id: rl_deny_user
runtime: list_match
layer: gateway
reason_code: GW_USER_DENY
risk_score: 100
body:
  list_name: blocked_users

# ---------- 累计 ----------
# 定义
cumulative_name: user_req_1h
dimension: user_id
window_kind: rolling
window_minutes: 60
threshold: 120
on_exceed_reason_code: GW_USER_RATE_1H
on_exceed_risk_score: 100

# 规则
rule_id: rl_rate_1h
runtime: cumulative
layer: gateway
body:
  cumulative_name: user_req_1h

# ---------- 扩展：同一定义、不同 value ----------
rule_id: rl_rate_by_app
runtime: cumulative
layer: gateway
body:
  cumulative_name: user_req_1h      # 共用窗口/阈值定义时需产品确认；更稳妥是单独 cumulative_name
  value_source:
    kind: var
    ref: app_id
```

> 同一 `cumulative_name` 下不同 `value_source` 会落到不同 Redis key；若阈值/窗口也应不同，应建 **不同 `cumulative_name`**。

---

## 8. 发布校验（摘要）

| # | 名单 | 累计 |
|---|------|------|
| 1 | `list_name` 唯一、active | `cumulative_name` 唯一、active |
| 2 | `body.list_name` 必填 | `body.cumulative_name` 必填 |
| 3 | `value_source` 合法（共有） | 同左 |
| 4 | — | rolling：`window_minutes` XOR `window_hours`；`W≤10080` |
| 5 | — | `calendar_day` 必 `timezone` |
| 6 | — | 改窗跨 1d 改 G → 新 `cumulative_name` |
| 7 | — | 租户 active 累计 ≤32（建议） |

---

## 9. 高并发（摘要）

| 项 | 做法 |
|----|------|
| 累计写 | Pipeline K 个 `CUM_INGEST`；Lua 原子 INCR+EXPIRE |
| 累计读 | Lua SUM；7d 窗 ≈1008 field（10 分钟桶） |
| 连接 | gateway-agent 集中 Redis；Cluster 同 AZ |
| 名单 | 读本地快照；keyword 大表用编译结构（P1） |
| 限制 | 每租户 active 累计/名单数量上限 |

---

## 10. 与现 PoC 差异

| 能力 | 现 PoC | 本设计 |
|------|--------|--------|
| 名单存储 | `tb_access_list(polarity,dimension,value)` 扁平 | `list_name` + meta + entry |
| 名单规则 | `sync-rules` → 12 固定 `rule_id` | `list_match` + `list_name` |
| 累计 | 无 | `tb_cumulative` + Redis |
| value | 隐含在 dimension | `dimension` + 可选 `value_source` |
| guard Lua | 写死 reason/rule_id | 来自快照配置 |

---

## 11. 文档索引

| 文档 | 内容 |
|------|------|
| [value-resolution.md](./value-resolution.md) | `value_source` 枚举与 resolve |
| [list-match.md](./list-match.md) | 名单细节、快照、Signal |
| [cumulative-counter.md](./cumulative-counter.md) | Slot/TTL/Lua、Redis |
| [groovy-l3-contract.md](./groovy-l3-contract.md) | `ctx.list` / `ctx.cumulative`（待实现） |

---

## 12. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-05-20 | 合并对话定稿：ListStore/CounterStore、value_source、1/10 分钟桶、无 calendar_week |
