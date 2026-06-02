# 名单规则与 ListStore（OpenSpec 草案）

与 [DESIGN.md §8.5.0.2](../DESIGN.md#8502-名单规则liststore)、[value-resolution.md](./value-resolution.md) 对齐。PoC **尚未实现**（现网仍为扁平 `tb_access_list` + 固定 rule 投影）。

| 项目 | 说明 |
|------|------|
| 状态 | 草案（设计冻结，待开发） |
| 定义 | `list_name` + 元数据 + 条目 |
| 规则 | `runtime=list_match`，body 至少 `list_name`；可选 `value_source`；**`bind_scope`** 见 [bind-scope.md](./bind-scope.md) |
| 平台 API | `boolean matchList(String value, String listName)` |

---

## 1. 架构原则

| 原则 | 说明 |
|------|------|
| 名单真源 | DB：`tb_access_list_meta` + `tb_access_list_entry`（名称见 §2） |
| 运行时 | 发布生成 **ListSnapshot**（管侧 JSON / 云侧物化）；**ListStore** 只读匹配 |
| 规则极简 | body 必填 `list_name`；阈值类逻辑在规则行 `risk_score` / `reason_code` |
| value | 默认由 `dimension` 从请求解析；规则可覆盖 → [value-resolution.md](./value-resolution.md) |
| 无手写 Lua 扫表 | `virbius-guard` / engine 调 ListStore 或等价实现 |

```text
value = resolveValue(rule, listDef, request)
matched = ListStore.match(value, listName)
→ ctx.list(listName) → Signal / 403 / Groovy
```

---

## 2. 数据模型

### 2.1 `tb_access_list_meta`

主键：`(tenant_id, list_name)`。

| 字段 | 必填 | 说明 |
|------|------|------|
| `list_name` | ✅ | 租户内唯一 |
| `polarity` | ✅ | `deny` \| `allow` |
| `dimension` | ✅ | 默认 value：`user_id` \| `device_id` \| `ip` \| `session_id` \| `keyword` \| `var:{name}` |
| `description` | | |
| `priority` | | 多名单顺序 |
| `status` | ✅ | `active` \| `disabled` |
| `version` | | 发布递增，缓存失效 |

### 2.2 `tb_access_list_entry`

主键：`(tenant_id, list_name, value)`。

| 字段 | 说明 |
|------|------|
| `value` | 静态条目（如 `u-evil`、`暴恐`）；**不是**规则里配的请求 value |

---

## 3. 规则（`tb_rule_history`）

### 3.1 最简

```yaml
rule_id: rl_block_users
runtime: list_match
layer: gateway
reason_code: GW_USER_DENY
risk_score: 100
body:
  list_name: blocked_users
```

### 3.2 指定 value 来源（可选）

```yaml
body:
  list_name: deny_app_ids
  value_source:
    kind: var
    ref: app_id
```

未写 `value_source` 时：`value = resolve(list.dimension, request)`，再 `matchList(value, listName)`。

---

## 4. ListStore

### 4.1 `match(tenant, listName, MatchContext)` → `ListMatchSnapshot`

内部：`value = resolveValue(...)` → 在快照/条目集中匹配。

| `dimension` | 匹配语义 |
|-------------|----------|
| `user_id` 等 | `value` 与条目 **相等** |
| `keyword` | `value` 为正文时，条目 **子串/词** 命中（PoC 子串） |
| `ip` | CIDR 或精确（P1） |

### 4.2 执行顺序

```text
1. 所有 active allow 名单（按 priority）
2. 累计 ingest（见累计文档）
3. 所有 active deny 名单
4. 云 Evaluate / Groovy
```

allow 命中策略（产品二选一，默认）：**仍 ingest 累计，但 deny 前 allow 可短路放行**。

---

## 5. 产物

发布写入 gateway 快照（示例）：

```json
{
  "tenant_id": "default",
  "lists": [
    {
      "list_name": "blocked_users",
      "polarity": "deny",
      "dimension": "user_id",
      "rule_id": "rl_block_users",
      "reason_code": "GW_USER_DENY",
      "risk_score": 100,
      "entries": ["u-evil"]
    }
  ]
}
```

`rule_id` / `reason_code` 来自绑定规则；命中返回配置字段，**不写死** `gw_content_deny`。

---

## 6. Signal metadata

```json
{
  "list_name": "blocked_users",
  "value": "u-evil",
  "value_source_kind": "request_field",
  "matched": true,
  "matched_entry": "u-evil"
}
```

---

## 7. 发布校验

| # | 规则 |
|---|------|
| 1 | `list_name` 存在且 active |
| 2 | `runtime=list_match` → `body.list_name` 必填 |
| 3 | 条目非空或允许空名单（仅告警） |
| 4 | `value_source` 合法 → 见 [value-resolution.md](./value-resolution.md) |

---

## 8. 与现 PoC 差异

| 现 PoC | 目标态 |
|--------|--------|
| `(polarity, dimension)` 扁平条目 | `list_name` 下条目 |
| `sync-rules` 投影 12 个 `rule_id` | 删除；快照由名单+绑定规则生成 |
| Lua 写死 reason | ListStore 返回配置 |

---

## 9. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v0.1 | 2026-05-20 | 初稿：list_name、ListStore、value_source |
