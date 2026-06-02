# 规则 value 解析（MatchContext / value_source）

名单 [list-match.md](./list-match.md) 与累计 [cumulative-counter.md](./cumulative-counter.md) 共用。PoC **尚未实现**。

---

## 1. 模型

平台提供两类只读能力（概念接口）：

```text
boolean ListStore.match(String value, String listName)
int     CounterStore.getCumulate(String value, String cumulativeName)  // 含 ingest 见累计文档
```

执行链：

```text
value = resolveValue(rule, definition, request)
if value 无效 → skip + 审计
result = match / getCumulate
RuleConditionEvaluator.evaluate(count, rule.condition)   # 累计规则
注入 ExecutionContext（ctx.list / ctx.cumulative）
规则引擎 / Groovy 使用 ctx 继续计算
```

Groovy/Lua **不**直连名单表或 Redis。

---

## 2. value 从哪来（优先级）

```text
if rule.body.value_source 存在:
    value = resolve(rule.body.value_source, request)
else:
    value = resolve(definition.dimension, request)    # 名单 dimension / 累计 dimension
```

| 层级 | 配置位置 | 作用 |
|------|----------|------|
| **定义** | `tb_access_list_meta.dimension` / `tb_cumulative.dimension` | 默认 value 解析方式 |
| **规则（可选）** | `body.value_source` | 覆盖默认，增强扩展性 |
| **请求** | Header、正文、`vars`、`context_bindings` | 每次请求的动态值 |
| **名单 entries** | 条目表 | 静态「要比对的目标集合」，不是本次请求的 value |

---

## 3. `value_source` 结构

```yaml
value_source:
  kind: <enum>
  ref: <string>      # 视 kind 而定，可省略
  value: <string>    # 仅 kind=literal 时使用
```

### 3.1 `kind` 枚举

| `kind` | `ref` / `value` | 解析结果 |
|--------|-----------------|----------|
| （省略整个 `value_source`） | — | 使用定义的 `dimension` |
| `request_field` | `user_id` \| `device_id` \| `session_id` \| `client_ip` | 标准请求字段 |
| `var` | 逻辑变量名，如 `app_id` | `vars[ref]`（经 `context_bindings`） |
| `header` | Header 名 | 如 `X-Custom-Id` |
| `query` | Query 参数名 | |
| `content` | — | 请求正文全文 |
| `literal` | `value: "..."` | 固定常量（少用） |

**Post-MVP**：`expr`（白名单组合，如 `user_id + ':' + app_id`）。

### 3.2 规范化与无效 value

| 规则 | 行为 |
|------|------|
| `trim` | 首尾空白 |
| 空 / null / 超长（**>256**） | 不 match、不 ingest/read；审计 `list_skipped` / `cumulative_skipped` |
| `ip` | 规范化为标准 IP 字符串 |

Redis Key / 审计中的 `value` 使用规范化后的字符串；`literal` 时审计可脱敏。

---

## 4. 与 Redis / 名单条目的关系

| 能力 | value 角色 | 静态配置 |
|------|------------|----------|
| 名单 | 本次候选（如当前 `user_id`） | `entries[]` 为黑名单/白名单集合 |
| 累计 | Redis 键中的一段（计数维度实例） | `threshold` 在 `tb_cumulative` |

累计 Redis Key（不再强制含 `dimension` 段，避免与 `value_source` 覆盖冲突）：

```text
virbius:cum:{tenant_id}:{cumulative_name}:{value}
```

同一 `cumulative_name`、不同规则若 `value_source` 不同（如 `user_id` vs `app_id`），自然落到不同 key，符合预期。

---

## 5. 注入上下文（ctx）

### 5.1 名单快照 `ctx.list(listName)`

| 字段 | 说明 |
|------|------|
| `matched` | bool |
| `value` | 实际用于匹配的 value |
| `value_source_kind` | 生效的来源 |
| `matched_entry` | 命中条目（可选） |
| `list_version` | 快照版本 |

### 5.2 累计快照 `ctx.cumulative(cumulativeName)`

| 字段 | 说明 |
|------|------|
| `count` | int |
| `threshold` / `exceeded` | |
| `value` | 用于计数的 value |
| `value_source_kind` | | 
| 其余 | 见 [cumulative-counter.md](./cumulative-counter.md) |

---

## 6. 扩展场景

| 场景 | 配置 |
|------|------|
| 默认按用户限流 | 累计定义 `dimension: user_id`；规则不写 `value_source` |
| 同一定义按 App 限流 | 规则 `value_source: { kind: var, ref: app_id }` |
| 全站总 QPS | `value_source: { kind: literal, value: "_global_" }`（需文档警示） |
| 同名单、不同字段两条规则 | 两规则同 `list_name`，不同 `value_source` |

---

## 7. 发布校验

| # | 规则 |
|---|------|
| 1 | `value_source.kind` 为合法枚举 |
| 2 | `literal` 累计：提示「全租户共用计数键」 |
| 3 | `kind=content` 时名单 `dimension` 宜为 `keyword`（警告） |
| 4 | 发布响应可带 `effective_value_source` 便于运维核对 |

---

## 8. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v0.1 | 2026-05-20 | 初稿：value_source 可选覆盖、ctx 注入 |
