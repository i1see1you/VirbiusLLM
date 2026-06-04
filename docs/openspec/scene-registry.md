# 场景注册表 `scene_registry`（OpenSpec 定稿）

与 [DESIGN.md §11.4](../DESIGN.md#114-管层网关安全规则绑定route--service--global)、[bind-scope.md](./bind-scope.md) 对齐。定义 **`app_id` → 多个 `scene_id`** 的归属与运行时分流；**`scene` 仅服务端注入**。

| 项目 | 说明 |
|------|------|
| 状态 | **设计冻结**（2026-05-20）；gateway 插件 / Compiler **待实现** |
| 关联 | [bind-scope.md](./bind-scope.md)、[value-resolution.md](./value-resolution.md)、[POC-SEED-API.md](../POC-SEED-API.md) |

---

## 1. 冻结决策（摘要）

| # | 决策 |
|---|------|
| 1 | **`scene_id` → `app_id` 多对一**：每个 scene **归属唯一** app；scene **不跨 app 复用** |
| 2 | **`app_id` → `scene_id` 一对多**：同一 app 可有多个 scene（chat / clinical / …） |
| 3 | **`app_id` 取值**来自 Bundle **`context_bindings`**（如 Header `X-App-Id`）；见 POC-SEED-API |
| 4 | **运行时 scene** 由 **`scene_registry` 解析** `(app_id, route_uri, match)`；**禁止**信任客户端 `X-Virbius-Scene` 选路 |
| 5 | **`gateway.routes`** 只管 URI 入口与 evaluate；**不**用 Scene Header 做 APISIX route 匹配 |
| 6 | **Service 规则**绑 **`bind_ref.app_ids`**（非 Key / consumer / upstream）；见 bind-scope §4.2 |
| 7 | **Route 规则**绑 **`bind_ref.scenes`** / `uris`；scene 为 registry 解析后的 `scene_id` |

---

## 2. 概念与命名

| 术语 | 含义 |
|------|------|
| **`app_id`** | 应用稳定标识；每请求标量，经 `context_bindings` → `vars.app_id` |
| **`scene_id`** | 策略场景 ID；Evaluate、`scope.scenes`、`bind_scope: route` 使用 |
| **`scene_registry`** | Bundle 元数据块；scene 归属与分流真源 |

**`scene_id` 命名建议**：`{app_id}_{mode}`（如 `medical-prod_clinical`），或全局唯一 ID + 显式 `app_id` 字段。**避免** PoC 裸名 `general_chat` / `medical_qa` 跨 app 共用。

**鉴权与策略分离**：

```text
api_key / consumer  ──鉴权插件──►  app_id（稳定）
                                    ├── scene_registry → scene_id
                                    └── bind_scope: service（app_ids）
```

Key 轮换只改鉴权映射；**不**改 `scene_registry` 与 Service 规则中的 `app_ids`。

---

## 3. 存储位置

写入 Registry **`tb_bundles.metadata_json`**，与 `context_bindings`、`gateway` 同级：

```yaml
metadata:
  context_bindings: { ... }
  scene_registry: { ... }
  gateway: { ... }
  scope:
    tenants: [default]
    scenes: [medical-prod_chat, medical-prod_clinical, beta_chat]  # 可由 registry 推导
```

**网关产物**（发布时 materialize，请求路径不访问 control API）：

```text
data/gateway/{tenant}-scene-registry.json
```

---

## 4. Schema

```yaml
scene_registry:
  version: 1
  fail_on_unknown_app: false       # true：vars.app_id 不在 registry 任何 owner 中 → 403
  fail_on_unresolved_scene: false  # true：无法解析 scene → 403

  scenes:
    <scene_id>:
      app_id: <string>             # 必填；owner
      default: <bool>              # 可选；该 app 的兜底 scene
      uris: [<path>, ...]          # 可选；空表示仅作 default
      priority: <int>              # 可选；同 app+uri 时越大越优先；默认 0
      match:                       # 可选；二次分流
        query: { <key>: <value> }
        headers: { <name>: <value> }   # 禁止 X-Virbius-Scene
```

### 4.1 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `version` | ✅ | schema 版本，当前 `1` |
| `fail_on_unknown_app` | △ | 未知 `app_id` 是否拒绝请求 |
| `fail_on_unresolved_scene` | △ | 无法解析 scene 是否拒绝 |
| `scenes.<id>.app_id` | ✅ | scene 的 owner；发布校验 **N:1** |
| `default` | △ | 该 app 内未命中更细规则时使用；**每 app 最多 1 个** |
| `uris` | △ | URI pattern（§5.1，与 gateway.routes / bind_ref 同语法）；须被 gateway 入口覆盖 |
| `priority` | △ | 同 `(app_id, uri)` 多条时的排序 |
| `match.query` / `match.headers` | △ | 全部满足才命中该 scene |

### 4.2 示例

```yaml
scene_registry:
  version: 1
  fail_on_unknown_app: false
  scenes:
    medical-prod_chat:
      app_id: medical-prod
      default: true
      uris: ["/v1/chat/completions"]
      priority: 0

    medical-prod_clinical:
      app_id: medical-prod
      uris: ["/v1/chat/completions"]
      priority: 10
      match:
        query: { mode: clinical }

    medical-prod_triage:
      app_id: medical-prod
      uris: ["/v1/medical/triage"]
      priority: 0

    beta_chat:
      app_id: beta
      default: true
      uris: ["/v1/chat/completions"]
      priority: 0
```

---

## 5. 运行时解析

### 5.1 输入

| 字段 | 来源 |
|------|------|
| `app_id` | `context_bindings` → `vars.app_id` |
| `route_uri` | APISIX / Kong 当前匹配 route 的 path（规范化） |
| `query` | 请求 query（仅 `match.query` 白名单键） |
| `headers` | 仅 `match.headers` 白名单；**不含**客户端 Scene |

### 5.2 URI 匹配

与 [bind-scope.md §5.1](./bind-scope.md#51-uri-匹配规则) 相同：去 query/fragment；精确或 `*` 前缀。

### 5.3 算法

```text
resolve_scene(app_id, route_uri, query, headers):

1. 若 app_id 为空：
     按租户策略 → 403 或跳过（生产建议 403）

2. 候选 ← { s | scenes[s].app_id == app_id }

3. 在候选中筛 route_uri 匹配的条目，按 priority 降序

4. 对每条候选项检查 match.query / match.headers（若配置）

5. 若命中 → 返回 scene_id

6. 否则若该 app 存在 default: true 的 scene → 返回该 scene_id

7. 否则：
     fail_on_unresolved_scene → 403
     或 tenant 级兜底 scene（可配）
```

### 5.4 输出与注入

| 输出 | 用途 |
|------|------|
| `scene_id` | `BindContext.scene`、Evaluate、`bind_scope: route` |
| `scene_source` | 审计：`rule` / `default` / `fallback` |
| 内部 Header | 可选向下游 set `X-Virbius-Scene`；**忽略**客户端同名校验前的自报值 |

---

## 6. 与 `gateway.routes` 的分工

| 配置 | 职责 |
|------|------|
| **`gateway.routes[]`** | 哪些 `uri` / `methods` 进入 `virbius-guard`、`evaluate`、upstream |
| **`scene_registry`** | 请求已进入网关后，**在该 app 下**选哪个 `scene_id` |

推荐 Route 配置：

```yaml
gateway:
  routes:
    - uri: /v1/chat/completions
      methods: [POST]
```

`gateway.routes` 仅声明 URI 入口；**scene 始终**由 `scene_registry` 在运行时解析，不在 route 上写死。

**禁止**（发布 **error**）：

```yaml
match:
  headers:
    X-Virbius-Scene: medical_qa   # 客户端可伪造，不得用于 APISIX 选路
```

PoC 种子已迁移为 `scene_registry` + 仅 `uri`/`methods` 的 `gateway.routes`。

---

## 7. 与 `bind_scope` 的配合

| `bind_scope` | 绑定字段 | 与 registry 关系 |
|--------------|----------|------------------|
| **global** | 可省略 | 不读 registry |
| **service** | **`app_ids[]`** | `vars.app_id ∈ app_ids`；对该 app **下所有 scene** 的请求生效 |
| **route** | **`scenes[]` / `uris[]`** | 用 **解析后的 `scene_id`** 或 uri 匹配 |

**优先级**：Route > Service > Global（见 bind-scope §2）。

**示例**：

```yaml
# 应用级限流：medical-prod 下所有 scene
bind_scope: service
bind_ref:
  app_ids: [medical-prod]

# 仅 clinical 子场景
bind_scope: route
bind_ref:
  scenes: [medical-prod_clinical]
```

---

## 8. Admin API（规划）

前缀：`/api/v1/admin/tenants/{tenantId}/bundles/{bundleId}/versions/{version}/metadata`

| 操作 | 方法 | 路径 |
|------|------|------|
| 读取（含 `scene_registry`） | GET | `.../metadata` |
| 整表替换 scene 注册 | PUT | `.../metadata/scene-registry?sync=true` |

`sync=true` 时：写入 `data/gateway/{tenant}-scene-registry.json`；合并更新 `scope.scenes`；可选触发 gateway 产物刷新。

运营台页签：**场景注册**（按 app 分组维护 scene 行）。

---

## 9. 发布校验

| # | 级别 | 规则 |
|---|------|------|
| 1 | error | 每个 `scene_id` 必须声明 `app_id` |
| 2 | error | 同一 `scene_id` 不得对应两个不同 `app_id` |
| 3 | error | 每个出现在 registry 的 `app_id` 至少有一个 scene |
| 4 | error | 每个 `app_id` 最多一个 `default: true` |
| 5 | error | `gateway.routes` 不得使用 `X-Virbius-Scene` 作为 match |
| 6 | error | `match.headers` 不得包含 `X-Virbius-Scene` |
| 7 | error | 所有 `scene_id` ∈ `scope.scenes`（或发布时自动合并进 scope） |
| 8 | warning | 同 `(app_id, uri)` 多条 scene 且 `priority` 相同 |
| 9 | error | registry 中 uri 须被 **gateway.routes** 覆盖（§5.1，与 bind-scope 同语法） |
| 10 | warning | `context_bindings` 未声明 `app_id` 但 registry 非空 |

---

## 10. PoC 迁移

| 现状 | 目标 |
|------|------|
| 裸 scene 名 `general_chat` / `medical_qa` | `{app_id}_{mode}` 或显式 owner |
| route `match.headers.X-Virbius-Scene` | `scene_registry` + `app_id` / uri / query |
| 每 scene 一条 APISIX route 且 scene 写死 | 单 uri route + `scene_registry` 运行时解析（推荐） |

**影子期（可选）**：并行记录旧 route 推导 scene 与 registry scene，一致后切换。

---

## 11. 实施顺序（参考）

| 阶段 | 内容 |
|------|------|
| **P1a** | schema、发布校验、gateway 产物 `{tenant}-scene-registry.json` |
| **P1b** | 插件 `resolve_scene()`；剥离客户端 Scene Header 选路 |
| **P1c** | Admin PUT + 运营台「场景注册」 |
| **P2** | Compiler scene_registry 产物；PoC 种子迁移；与 `bind_ref.app_ids` 联调 |

---

## 12. 交叉引用

| 文档 | 内容 |
|------|------|
| [bind-scope.md](./bind-scope.md) | Service **`app_ids`**、Route **`scenes`**、`BindContext` |
| [value-resolution.md](./value-resolution.md) | `vars.app_id`、累计 / 名单 value |
| [groovy-l3-contract.md](./groovy-l3-contract.md) | `ctx.scene()`、`ctx.var("app_id")` |
| [POC-SEED-API.md](../POC-SEED-API.md) | `context_bindings` Admin API |
