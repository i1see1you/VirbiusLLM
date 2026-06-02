# 规则绑定范围 `bind_scope`（OpenSpec 定稿）

与 [DESIGN.md §11.4](../DESIGN.md#114-管层网关安全规则绑定route--service--global) 对齐。**名单（`list_match`）与累计（`cumulative`）共用**同一绑定模型；运营台字段一致。

| 项目 | 说明 |
|------|------|
| 状态 | **设计冻结**（2026-05-20）；gateway-agent / engine **待实现** |
| 关联 | [list-and-cumulative-rules.md](./list-and-cumulative-rules.md)、[cumulative-counter.md](./cumulative-counter.md)、[list-match.md](./list-match.md)、[value-resolution.md](./value-resolution.md) |

---

## 1. 冻结决策（摘要）

| # | 决策 |
|---|------|
| 1 | **`bind_scope` 只决定「是否对本请求 ingest / 判定」**；**不**改变 Groovy L3 终判位置（仍在 cloud `virbius-engine`） |
| 2 | 同一 **`(cumulative_name, value)`** 每请求最多 **ingest 一次**（同 scope 匹配上下文中 key 去重） |
| 3 | **Global 与 Route 级限流使用不同 `cumulative_name`**，除非日后显式采用 Redis key scope 段（P2，非 MVP） |
| 4 | **`scene` 由服务端根据 AppId / 路由映射注入**（DESIGN §11.4）；累计 / 名单 bind **不信任**客户端伪造 Header |
| 5 | **`list_match` 与 `cumulative` 共用 `bind_scope` + `bind_ref`** |

---

## 2. 三层 `bind_scope`

与 APISIX / Kong / Nginx / Envoy 的 Global → Service → Route 概念对齐；Virbius **租户（tenant）已是硬隔离**，Service **不是** tenant 本身。

| `bind_scope` | 作用范围 | 典型用途 | `bind_ref` 主要字段 |
|--------------|----------|----------|---------------------|
| **`global`** | **租户内**全部经过 gateway 的流量 | 全站用户 QPS、全局 IP 黑名单、默认累计 | 可省略，或 `scenes: ["*"]` |
| **`service`** | 某一 **upstream / consumer / API Key 组** | 按调用方配额、按上游集群策略（DESIGN §11.4.4） | `upstream_id`、`consumer_id`、`api_key_group`（见 §4） |
| **`route`** | 特定 API 路径 / 场景 | `/v1/chat/completions` 专用限流、scene 增强 | `scenes[]`、**可选** `uris[]`（**不含** `methods`） |

**冲突优先级（规则 / 插件继承）**：**Route > Service > Global**（与 APISIX/Kong 惯例一致）。

**MVP 建议**：先实现 **`global` + `route`**；`service` 与 upstream / consumer 字段在 P1 末或 P2 落地。

---

## 3. 规则 body 扩展（名单与累计共用）

在 `tb_rule_history` 规则行（或 Bundle `rules[]`）上配置，**不**写入名单 entries / 累计窗口定义。

```yaml
rule_id: rl_chat_rate_1h
runtime: cumulative          # 或 list_match
layer: gateway               # 或 cloud（仅 read / match，ingest 仍在 gateway）
bind_scope: route            # global | service | route
bind_ref:                    # 视 bind_scope；global 可省略
  scenes: [general_chat]     # route：可选；service / global 按需
  uris: ["/v1/chat/completions"]
body:
  cumulative_name: chat_user_req_1h
  condition:
    compare_op: gte
    threshold: 120
```

与既有 Bundle 字段关系：

- 历史字段 `scope.scenes` / `scope.tenants` / `scope.roles` **保留**；Compiler 将其**投影**为 `bind_scope` + `bind_ref` 写入 gateway 产物。
- **`layer`**（edge / gateway / cloud）表示**执行面**；**`bind_scope`** 表示**流量范围**，二者正交。

---

## 4. `bind_ref` 字段

### 4.1 Route

| 字段 | 必填 | 说明 |
|------|------|------|
| `scenes` | △ | 场景 ID 列表；运维 / 编译 hint；**无 `uris` 时**用于运行时匹配 |
| `uris` | △ | URI 路径列表；**运行时匹配优先**（§5） |

**至少**提供 `scenes` 或 `uris` 之一（发布校验）。

**不含 `methods`**：Route 绑定不区分 HTTP 方法；方法级策略由 APISIX Route 配置或网关原生插件承担。

### 4.2 Service（P1+）

| 字段 | 说明 |
|------|------|
| `upstream_id` | 上游集群标识（如 `azure-openai`、`vllm-local`） |
| `consumer_id` | Kong consumer / 调用方 ID |
| `api_key_group` | API Key 分组名（与 `context_bindings` / 鉴权插件一致） |

**至少**一项非空。匹配请求上下文中的 upstream / consumer / key 组（由插件注入 `BindContext`，见 §6）。

### 4.3 Global

`bind_ref` 可省略；若存在 `scenes: ["*"]` 表示租户内全部 scene。

---

## 5. Route 运行时匹配（**uri 优先**）

请求进入 gateway-agent 时携带 **`BindContext`**（§6）。对 `bind_scope: route`：

```text
1. 若 bind_ref.uris 非空：
     用 request.route_uri 匹配 uris（见 §5.1）→ 命中则匹配，否则不匹配
     （此时 bind_ref.scenes 不参与过滤，仅作运营 / 编译标注）

2. 否则若 bind_ref.scenes 非空：
     用 request.scene 匹配 scenes（支持 "*"）

3. 否则：发布校验应拒绝（route 无有效 bind_ref）
```

### 5.1 URI 匹配规则

| 规则 | 说明 |
|------|------|
| 规范化 | 去掉 query / fragment；保留 path；大小写敏感（与 APISIX route 一致） |
| 模式 | **精确匹配** 或 **前缀匹配**（`uri` 以 `*` 结尾表示前缀，如 `/v1/chat/*`） |
| 来源 | **`route_uri` 来自 APISIX/Kong 当前匹配 route**，非客户端任意 Header |

**为何 uri 优先**：APISIX Route 的真值是 path；`scene` 为产品语义，由**路由表 / AppId 映射**注入，无 uri 约束时退化为 scene 匹配。

---

## 6. `BindContext`（运行时）

gateway 插件 → gateway-agent 传入（Evaluate 与名单 / 累计检查**共用**）：

| 字段 | 来源 | 说明 |
|------|------|------|
| `tenant_id` | 插件 / 配置 | 租户 |
| `scene` | **服务端映射**（路由 / AppId） | 禁止信任客户端自报 |
| `route_uri` | **当前 APISIX/Kong 匹配 route 的 uri/path** | Route 匹配主字段 |
| `role` | ControlContext | 默认 `user` |
| `upstream_id` | 插件 / service 配置 | Service 匹配（P1+） |
| `consumer_id` | 鉴权插件 | Service 匹配（P1+） |
| `api_key_group` | 鉴权 / 映射 | Service 匹配（P1+） |

`access_lists` / 累计模块在 Phase A（ingest）与 Phase B（判定）前调用：

```text
matches_bind(rule.bind_scope, rule.bind_ref, bind_ctx) == true
```

---

## 7. 与 ingest / 判定的关系

与 [cumulative-counter.md §7.4](./cumulative-counter.md#74-执行顺序建议) 两阶段模型一致：

```text
allow 名单（按 bind 过滤）
→ Phase A：ingest 全部 active 累计（按 bind_scope + ingest_targets 过滤，key 去重）
→ deny 名单（按 bind 过滤）
→ Phase B：cumulative 规则 read / 判定（按 bind_scope 过滤）
→ cloud Evaluate（engine 内 cumulative / list 规则再按 bind 过滤，只 read）
```

| 环节 | bind_scope 作用 |
|------|-----------------|
| **Ingest** | 仅对 **bind 匹配** 的累计定义 / ingest target 写 Redis |
| **名单 match** | 仅对 bind 匹配的 `list_match` 规则执行 |
| **累计判定** | 仅对 bind 匹配的 `cumulative` 规则 read + compare |
| **Groovy L3** | **不**解析 bind_scope；读 `ctx.signals()` / 预取快照 |

同一请求可同时命中 **Global + Route** 等不同 scope 的规则（不同 `cumulative_name` / `list_name`）：各自 ingest / 判定，ActionMerge 按 **max risk_score** 合并。

---

## 8. 累计命名约定（Global vs Route）

| 范围 | `cumulative_name` 建议 | 示例 |
|------|------------------------|------|
| Global | `{biz}_global` 或 `{dimension}_req_{window}_global` | `user_req_1h_global` |
| Route | `{biz}_route_{scene}` 或含 uri 语义 | `chat_user_req_1h` + bind `uris: [/v1/chat/completions]` |

发布校验：**warning** Global / Route 规则共用同一 `cumulative_name`；**error** 若窗口 / dimension 不同却共名。

Redis key 仍为 `virbius:cum:{tenant}:{cumulative_name}:{value}`（暂无 scope 段）。

---

## 9. 发布校验（摘要）

| # | 规则 |
|---|------|
| 1 | `bind_scope ∈ { global, service, route }` |
| 2 | `route` → `bind_ref.scenes` 或 `bind_ref.uris` 至少一项 |
| 3 | `service` → `upstream_id` / `consumer_id` / `api_key_group` 至少一项 |
| 4 | `global` → 勿将 tenant 写入 `bind_ref` 冒充 service |
| 5 | `runtime ∈ { list_match, cumulative }` 时规则必须带合法 `bind_scope`（MVP 默认 `global` 可配置缺省） |
| 6 | Compiler 将 bind 投影到 `apisix-global-rules.json` / `apisix-routes-*.json`（DESIGN §8.10） |

---

## 10. 实施顺序（参考）

| 阶段 | 内容 |
|------|------|
| **P0** | 两阶段 ingest / evaluate + `(cumulative_name, value)` key 去重 + `cumulatives[]` 产物 |
| **P1a** | `BindContext.route_uri` + `bind_scope` global / route + uri 优先匹配 |
| **P1b** | 名单 `list_match` 共用 bind；ops 表单 |
| **P2** | `service`（upstream / consumer / api_key_group）；Redis key scope 段（可选） |

---

## 11. 交叉引用

| 文档 | 内容 |
|------|------|
| [list-and-cumulative-rules.md](./list-and-cumulative-rules.md) | 定义 + 规则两层、ingest / read |
| [cumulative-counter.md](./cumulative-counter.md) | Redis 桶、CounterStore |
| [list-match.md](./list-match.md) | 名单快照 |
| [DESIGN.md §11.4](../DESIGN.md#114-管层网关安全规则绑定route--service--global) | APISIX/Kong 产物与插件 |
