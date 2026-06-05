# 规则绑定范围 `bind_scope`（OpenSpec 定稿）

与 [DESIGN.md §11.4](../DESIGN.md#114-管层网关安全规则绑定route--service--global) 对齐。**名单（`list_match`）与累计（`cumulative`）共用**同一绑定模型；运营台字段一致。

| 项目 | 说明 |
|------|------|
| 状态 | **设计冻结**（2026-05-20）；Service **`app_ids`**、scene 解析见 [scene-registry.md](./scene-registry.md)；gateway-agent / engine **待实现** |
| 关联 | [scene-registry.md](./scene-registry.md)、[list-and-cumulative-rules.md](./list-and-cumulative-rules.md)、[cumulative-counter.md](./cumulative-counter.md)、[list-match.md](./list-match.md)、[value-resolution.md](./value-resolution.md) |

---

## 1. 冻结决策（摘要）

| # | 决策 |
|---|------|
| 1 | **`bind_scope` 只决定「是否对本请求 ingest / 判定 / 进 prompt 矩阵」**；**L3 合并**在 cloud `PolicyMerger`（Java），见 [rule-hit-merge.md](./rule-hit-merge.md) |
| 2 | 同一 **`(cumulative_name, value)`** 每请求最多 **ingest 一次**（同 scope 匹配上下文中 key 去重） |
| 3 | **Global 与 Route 级限流使用不同 `cumulative_name`**，除非日后显式采用 Redis key scope 段（P2，非 MVP） |
| 4 | **`scene_id` 由服务端经 [scene-registry.md](./scene-registry.md) 解析**；累计 / 名单 bind **不信任**客户端伪造 Header |
| 5 | **`list_match` 与 `cumulative` 共用 `bind_scope` + `bind_ref`** |
| 6 | **`bind_scope: service` 仅使用 `bind_ref.app_ids`**；不绑 `upstream_id` / `consumer_id` / `api_key_group`（鉴权产物，Key 轮换不稳定） |

---

## 2. 三层 `bind_scope`

与 APISIX / Kong / Nginx / Envoy 的 Global → Service → Route 概念对齐；Virbius **租户（tenant）已是硬隔离**，Service **不是** tenant 本身。

| `bind_scope` | 作用范围 | 典型用途 | `bind_ref` 主要字段 |
|--------------|----------|----------|---------------------|
| **`global`** | **租户内**全部经过 gateway 的流量 | 全站用户 QPS、全局 IP 黑名单、默认累计 | 可省略，或 `scenes: ["*"]` |
| **`service`** | **指定应用（`app_id`）** | 按应用配额、应用级名单 / 累计 | **`app_ids[]`**（见 §4.2） |
| **`route`** | 特定 **scene** 或 API 路径 | 应用内子场景加强、URI 专用限流 | `scenes[]`、**可选** `uris[]`（**不含** `methods`） |

**冲突优先级（规则 / 插件继承）**：**Route > Service > Global**（与 APISIX/Kong 惯例一致）。

**MVP 建议**：先实现 **`global` + `route`**；**`service` + `app_ids`** 在 P1 末或 P2 与 [scene-registry.md](./scene-registry.md) 一并落地。

---

## 3. 规则 body 扩展（名单与累计共用）

在 `tb_rule_history` 规则行（或 Bundle `rules[]`）上配置，**不**写入名单 entries / 累计窗口定义。

```yaml
rule_id: rl_medical-prod_1h
runtime: cumulative          # 或 list_match
layer: gateway               # 或 cloud（仅 read / match，ingest 仍在 gateway）
bind_scope: service          # global | service | route
bind_ref:
  app_ids: [medical-prod]
body:
  cumulative_name: app_req_1h_medical-prod
  value_source: { kind: var, ref: app_id }
  condition:
    compare_op: gte
    threshold: 1000
```

```yaml
rule_id: rl_medical-prod_clinical
runtime: cumulative
layer: gateway
bind_scope: route
bind_ref:
  scenes: [medical-prod_clinical]
body:
  cumulative_name: clinical_user_req_1h
  condition:
    compare_op: gte
    threshold: 120
```

与既有 Bundle 字段关系：

- 历史字段 `scope.scenes` / `scope.tenants` / `scope.roles` **保留**；`scope.scenes` 与 **scene_registry** 对齐（见 scene-registry §3）。
- **`layer`**（edge / gateway / cloud）表示**执行面**；**`bind_scope`** 表示**流量范围**，二者正交。

---

## 4. `bind_ref` 字段

### 4.1 Route

| 字段 | 必填 | 说明 |
|------|------|------|
| `scenes` | △ | **`scene_id` 列表**（registry 解析结果）；**无 `uris` 时**用于运行时匹配 |
| `uris` | △ | URI 路径列表；**运行时匹配优先**（§5） |

**至少**提供 `scenes` 或 `uris` 之一（发布校验）。

**不含 `methods`**：Route 绑定不区分 HTTP 方法；方法级策略由 APISIX Route 配置或网关原生插件承担。

### 4.2 Service

| 字段 | 必填 | 说明 |
|------|------|------|
| **`app_ids`** | ✅ | 应用 ID 列表；`vars.app_id ∈ app_ids` 时匹配 |

**至少一项非空**（发布校验）。`app_id` 来自 **`context_bindings`**（如 Header `X-App-Id`），见 [POC-SEED-API.md](../POC-SEED-API.md)。

**匹配语义**：

```text
matchesService(bind_ref, ctx):
  app_id = ctx.vars["app_id"]
  return app_id 非空 且 app_id ∈ bind_ref.app_ids
```

对该 app **下所有 `scene_id`** 的请求均可命中（应用壳策略）。仅某一子场景生效时使用 **`bind_scope: route` + `scenes`**。

**已废弃（新规则 error，旧规则 migration warning）**：

| 字段 | 说明 |
|------|------|
| ~~`upstream_id`~~ | 上游集群；保留在 APISIX upstream 配置，**不参与** Virbius Service bind |
| ~~`consumer_id`~~ | Kong consumer；鉴权层使用 |
| ~~`api_key_group`~~ | Key 分组；Key 轮换不稳定，**不**绑策略规则 |

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
     用 request.scene（scene_registry 解析的 scene_id）匹配 scenes（支持 "*"）

3. 否则：发布校验应拒绝（route 无有效 bind_ref）
```

### 5.1 URI 匹配规则（统一语法）

以下三处共用 **同一套 URI pattern**，实现见 `BindScope.validateUriPattern` / `uriMatches` / `patternCovers`：

| 配置位置 | 用途 |
|----------|------|
| **`gateway.routes[].uri`** | APISIX 入口：哪些 path 进入 evaluate |
| **`scene_registry.scenes.*.uris`** | 在该 app 下按 path 选 scene |
| **`bind_ref.uris`**（`bind_scope: route`） | 规则是否作用于当前 path |

| 规则 | 说明 |
|------|------|
| 规范化 | 去掉 query / fragment；保留 path；大小写敏感（与 APISIX route 一致） |
| 精确 | 如 `/v1/chat/completions` |
| 前缀 | **`/` 路径 + 末尾单个 `*`**，如 `/v1/chat/*`（禁止 `**`、中间 `*`） |
| 运行时 | `route_uri` 为**真实请求 path**；各 pattern 与之匹配 |
| 覆盖 | `gateway.routes` 的 pattern **覆盖** scene / 规则 pattern 时，后者方可配置（保存校验 **error**） |

**覆盖语义**：gateway pattern `G` 覆盖 rule/scene pattern `R`，当且仅当「凡匹配 `R` 的请求 path，也会匹配 `G`」。例：`G=/v1/chat/*` 覆盖 `R=/v1/chat/completions`；`G=/v1/chat/completions` **不**覆盖 `R=/v1/chat/*`。

**推荐**：`gateway.routes` 用较宽前缀（如 `/v1/chat/*`）；`scene_registry` / route 规则在其下用精确 path 或 query 细分。

| 来源 | **`route_uri` 来自 APISIX/Kong 当前匹配 route**，非客户端任意 Header |

**为何 uri 优先**：APISIX Route 的真值是 path；`scene_id` 为策略语义，由 **[scene-registry](./scene-registry.md)** 在已知 `app_id` 下解析；无 uri 约束时退化为 scene 匹配。

---

## 6. `BindContext`（运行时）

gateway 插件 → gateway-agent 传入（Evaluate 与名单 / 累计检查**共用**）：

| 字段 | 来源 | 说明 |
|------|------|------|
| `tenant_id` | 插件 / 配置 | 租户 |
| `vars.app_id` | **`context_bindings`** | 应用标识；Service bind **主字段** |
| `scene` | **[scene-registry](./scene-registry.md) 解析** | `scene_id`；禁止信任客户端自报 |
| `route_uri` | **当前 APISIX/Kong 匹配 route 的 uri/path** | Route 匹配主字段 |
| `role` | ControlContext | 默认 `user` |

**不再用于 Service bind 匹配**（可保留于日志 / 网关配置）：`upstream_id`、`consumer_id`、`api_key_group`。

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
| **名单 match** | 仅对 bind 匹配的名单 / lua 脚本规则执行 |
| **累计判定** | 仅对 bind 匹配的累计 / 脚本规则 read + compare |
| **prompt 矩阵** | 仅 **bind 匹配** 的 `runtime=prompt` 规则进入本次 1B 矩阵 |
| **L3 合并** | **`PolicyMerger`（Java）**；不解析 bind_scope；读全部 Signal 池 |

同一请求可同时命中 **Global + Service + Route** 等不同 scope 的规则（不同 `cumulative_name` / `list_name`）：各自 ingest / 判定，ActionMerge 按 **max risk_score** 合并。

---

## 8. 累计命名约定（Global vs Service vs Route）

| 范围 | `cumulative_name` 建议 | 示例 |
|------|------------------------|------|
| Global | `{biz}_global` 或 `{dimension}_req_{window}_global` | `user_req_1h_global` |
| Service | `app_req_{window}_{app_id}` | `app_req_1h_medical-prod` + bind `app_ids: [medical-prod]` |
| Route | `{biz}_route_{scene_id}` 或含 uri 语义 | `clinical_user_req_1h` + bind `scenes: [medical-prod_clinical]` |

发布校验：**warning** 不同 bind_scope 规则共用同一 `cumulative_name`；**error** 若窗口 / dimension 不同却共名。

Redis key 仍为 `virbius:cum:{tenant}:{cumulative_name}:{value}`（暂无 scope 段）。

---

## 9. 发布校验（摘要）

| # | 规则 |
|---|------|
| 1 | `bind_scope ∈ { global, service, route }` |
| 2 | `route` → `bind_ref.scenes` 或 `bind_ref.uris` 至少一项 |
| 3 | **`service` → `bind_ref.app_ids` 至少一项**；**禁止**新规则使用 `upstream_id` / `consumer_id` / `api_key_group` |
| 4 | `global` → 勿将 tenant 写入 `bind_ref` 冒充 service |
| 5 | `runtime ∈ { list_match, cumulative, lua, groovy, prompt }` 等需 bind 时须合法 `bind_scope`（缺省 `global`） |
| 6 | `bind_ref.scenes` 中 scene_id 须存在于 **scene_registry** / `scope.scenes` |
| 7 | `bind_ref.uris` 每项须被 **gateway.routes** 某条 uri **覆盖**（§5.1） |
| 8 | Compiler 将 bind 投影到 gateway 产物（DESIGN §8.10） |

---

## 10. 实施顺序（参考）

| 阶段 | 内容 |
|------|------|
| **P0** | 两阶段 ingest / evaluate + `(cumulative_name, value)` key 去重 + `cumulatives[]` 产物 |
| **P1a** | `BindContext.route_uri` + `bind_scope` global / route + uri 优先匹配 |
| **P1b** | 名单 / 脚本规则共用 bind；ops 表单 |
| **P1c** | [scene-registry.md](./scene-registry.md) + **`service` + `app_ids`** |
| **P2** | 废弃 legacy Service 三字段；PoC route Scene Header 迁移；Redis key scope 段（可选） |

---

## 11. 交叉引用

| 文档 | 内容 |
|------|------|
| [scene-registry.md](./scene-registry.md) | **`app_id` / `scene_id` 归属与解析** |
| [list-and-cumulative-rules.md](./list-and-cumulative-rules.md) | 定义 + 规则两层、ingest / read |
| [cumulative-counter.md](./cumulative-counter.md) | Redis 桶、CounterStore |
| [list-match.md](./list-match.md) | 名单快照 |
| [value-resolution.md](./value-resolution.md) | `vars.app_id`、value_source |
| [DESIGN.md §11.4](../DESIGN.md#114-管层网关安全规则绑定route--service--global) | APISIX/Kong 产物与插件 |
