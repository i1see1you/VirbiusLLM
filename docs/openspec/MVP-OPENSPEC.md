# VirbiusLLM MVP OpenSpec

| 属性 | 值 |
|------|-----|
| 版本 | MVP-1.12 |
| 对齐设计 | [DESIGN.md v2.21](../DESIGN.md) §7.1.1、§8.5.2、§8.10.2.5a、§11.6.0、[rule-rollout.md](./rule-rollout.md) |
| PoC 代码 | [POC-REPO.md](../POC-REPO.md) |
| 状态 | 已冻结（2026-05-20） |

---

## 1. 范围

### 1.1 Must（首期验收）

- 端 **L0**：`virbius-core` + manifest 同步；PoC **方案 B+**（Control HTTP 直拉，§4.8）；终态 CDN `edge-manifest` + bin（§8.10.2）
- 控制面：**virbius-control**（Registry + Admin 单进程）+ `rule_history` + compiler + **PublishOrchestrator**
- 管层：**APISIX** + `virbius-guard` + **gateway-agent** + etcd 路由
- 云：**virbius-engine** RuleCache + **L1 单 Prompt** + Groovy L3
- 运行时：**rollout_state**（`draft` / `dry_run` / `canary` / `full` / `disabled`）；执行面导出 `enforce_mode`（[rule-rollout.md](./rule-rollout.md)）
- 审计：消息总线 ingest（默认 **Redis Stream**，可选 Kafka）+ 本地 jsonl 备档

### 1.2 Stretch（不阻塞验收）

- Kong + **KongDeckAdapter**（decK）
- 端侧双平台 SDK 壳（iOS/Android 其一）

### 1.3 非目标

- Kong Admin API、Kafka/ClickHouse 审计、Flink 自动晋升
- L2 Prompt、hold-then-release、Agent 草案、OPA/Rego
- 请求路径查 Registry/DB（engine 仅发布时刷 RuleCache）

---

## 2. 冻结决策（§11.6.0）

| ID | 决策 |
|----|------|
| F-01 | APISIX 全链路必达 |
| F-02 | Kong decK stretch，W9–10 |
| F-03 | 云检测 = **L1 单 Prompt** + 1 Groovy L3 |
| F-04 | `rule_id` **租户内唯一**；版本 = `rule_revision` |
| F-05 | 审计不含 `bundle_version`；含 `rule_revision` |
| F-06 | engine = RuleCache ← DB；端/管 = 文件推送 |
| F-07 | Kong 仅 **KongDeckAdapter** |
| F-08 | PoC：单 tenant `default`，单 Bundle `poc-default` |
| F-09 | **virbius-control**：Registry + Admin **单 Java 进程**；对外 **一套 HTTP**（`:8080`） |
| F-10 | **`trace_id`**：优先 App/SDK；HTTP 缺 Header 时**管侧生成**；**非法格式仍 400**；审计标 `trace_id_source` |
| F-11 | **`ControlContext` 端管云公用**：`user_id` / `device_id` 与 `trace_id` 同源透传；审计**可选写入** |
| F-12 | **引擎执行安全（MVP）**：Groovy 沙箱白名单 `ctx` + 禁 IO/网络/反射；Prompt 仅内置 Runner + 结构化模板 |
| F-13 | **`rule_history` 唯一真源**；~~`rule_runtime`~~ 废弃（§8.1、DESIGN §8.5.2） |
| F-14 | **virbius-gateway-agent** 实现语言：**Rust**（与 `virbius-core` 同工具链；sidecar HTTP → engine gRPC/HTTP） |
| F-15 | **端 manifest 同步（PoC）**：方案 **B+** — Control `policy-version` + 条件 `manifest`；租户级 Bearer（可选）；Init 持 `edge_api_key`（**不下发** manifest）；详见 §4.8、DESIGN §8.10.2.5a |

---

## 3. 组件与契约索引

| 组件 | 协议 | 规范文件 |
|------|------|----------|
| **virbius-control** | HTTP/JSON | [registry.openapi.yaml](./registry.openapi.yaml)（含 Edge 拉取 §4.8、`/api/v1/edge/*`） |

**部署**：一个 Spring Boot 进程；`/api/v1/*` 为规则与 Admin API；`/ui` → 静态 **`ops.html`** 运营台（左侧导航 + 策略上线）。逻辑模块 `registry-core`（写 DB）与 `admin-ui`（静态资源 + 鉴权）**不得**拆成两套规则真源。详见 DESIGN §7.1.1、[rule-rollout.md §8.3](./rule-rollout.md)。

| 逻辑模块（进程内） | 说明 |
|--------------------|------|
| registry-core | 规则 CRUD、`rule_history`、发布状态机 |
| admin-ui | 运营台静态资源 + 鉴权 |
| deploy-orchestrator | PublishOrchestrator（可同进程库） |
| gateway-agent | HTTP/JSON | [gateway-agent.openapi.yaml](./gateway-agent.openapi.yaml) |
| virbius-engine Evaluate | gRPC | [evaluate.proto](./evaluate.proto) |
| virbius-engine 运维 | HTTP | [engine-admin.openapi.yaml](./engine-admin.openapi.yaml) |
| virbius-core | Native C ABI | §4 |
| virbius-compiler | CLI | §5 |
| virbius-deploy | 内部 Java API + Adapter | §6 |
| 审计事件 | jsonl | [schemas/audit-event.schema.json](./schemas/audit-event.schema.json) |
| 防控请求上下文 | JSON Schema | [schemas/control-context.schema.json](./schemas/control-context.schema.json) |

---

## 4. 端侧 Native API（virbius-core）

### 4.1 类型

```c
typedef struct {
  const char* user_id;      // 可选
  const char* device_id;    // 可选
  const char* scene;        // 可选
  const char* trace_id;     // 可选；SDK 入口为空则自动生成（§4.5）
} virbius_scan_ctx;

typedef struct {
  virbius_action action;    // ALLOW | BLOCK
  const char* rule_id;
  int32_t rule_revision;
  const char* reason_code;
  const char* layer;        // 固定 "edge"
  const char* trace_id;     // 最终 trace（空 ctx 时 SDK 生成）；须 virbius_free_string
} virbius_scan_result;
```

### 4.2 函数

| 函数 | 说明 |
|------|------|
| `virbius_init_config_json(const char* json)` | **生产**：JSON [`EdgeInitConfig`]（§4.8），sync + 加载 manifest |
| `virbius_init(const char* url_or_path)` | 遗留：Control URL 或离线 manifest 路径 |
| `virbius_scan(ctx, text, &result)` | L0 检测；**`trace_id` 为空时 SDK 自动生成** |
| `virbius_reload()` | 再 sync + 热更新本地缓存 |
| `virbius_free_string(char* p)` | 释放 SDK 分配的字符串 |

[`EdgeInitConfig`] JSON 字段（Rust / C 共用）：

| 字段 | 必填 | 说明 |
|------|------|------|
| `cache_dir` | ✅ | 本地 manifest + `meta.json` 目录 |
| `control_base_url` | △ | 与 `app_id` 同设时走远程 sync |
| `tenant_id` | | 默认 `default` |
| `app_id` | △ | 远程 sync 必填；指定 per-app manifest |
| `edge_api_key` | △ | Control auth 开启时必填；租户级 Bearer |
| `offline_manifest_path` | | 设则跳过 Control，直读本地文件 |

### 4.3 检测顺序

1. `blacklist`（user_id / device_id / keyword）→ BLOCK  
2. `words` / `regex` → BLOCK  
3. ALLOW  

### 4.4 编译产物

见 [schemas/edge-manifest.schema.json](./schemas/edge-manifest.schema.json)。  
**要求**：bin 命中元数据携带 `rule_id` + `rule_revision`。

### 4.5 `trace_id` 传播（F-10）

**目标**：端/管/云用 `trace_id` 关联排障；App 显式传参时全链路一致；缺省时 **SDK / 网关兜底** 并标 `trace_id_source`。

**生成优先级**（同一请求只取一层）：

```text
client → sdk（scan 入口 ctx.trace_id 为空）→ gateway（HTTP 仍无合法 Header）
```

| 规则 | 约定 |
|------|------|
| **格式** | **UUID v4**（36 字符，version=4）；禁止空串、可预测序列 |
| **端 L0（SDK）** | `ctx.trace_id` 为空 → **SDK 自动生成**；edge-audit 写 `trace_id_source=sdk` |
| **管层 HTTP** | Header **合法** → 透传（`trace_id_source=client`）；**缺失** → 网关生成 + WARN（`gateway`） |
| **管层（格式）** | Header **存在但非法** → **400**，不生成 |
| **云 engine** | 使用管层最终 `trace_id` |
| **审计** | 建议写 `trace_id_source`：`client` \| `sdk` \| `gateway` |

**网关 400**（仅非法格式）：

```json
{ "code": "INVALID_ARGUMENT", "message": "invalid X-Virbius-Trace-Id" }
```

网关生成时可 **回传** `X-Virbius-Trace-Id`。未挂 `virbius-guard` 的路由不要求 trace。

**推荐集成（端管云对齐）**：

```text
trace_id := uuid_v4()   // 或由 SDK 在 scan 内生成
virbius_scan({ trace_id, ... }, text)
  → BLOCK: edge-audit（trace_id + trace_id_source）
  → ALLOW:  X-Virbius-Trace-Id: trace_id
```

**端 block 不上行**：管侧生成**不适用**；须 App/SDK 在端侧生成 trace（优先级 1–2）。

### 4.6 `ControlContext` 防控请求上下文（F-11）

端/管/云共用**同一语义**的防控请求上下文（非 Evaluate 全文；`content` 仍为独立字段）。

| 字段 | 端 `virbius_scan_ctx` | HTTP Header | `EvaluateRequest` | 审计 jsonl |
|------|----------------------|-------------|-------------------|------------|
| `trace_id` | SDK 可生成 | 缺则**网关生成**；非法 **400** | ✅ | ✅ 必填 |
| `trace_id_source` | `client`/`sdk` | `client`/`gateway` | — | 建议写入 |
| `tenant_id` | App 配置 | `X-Virbius-Tenant`（网关注入） | ✅ | ✅ 必填 |
| `scene` | 可选 | `X-Virbius-Scene`（网关注入） | ✅ | ✅ 必填 |
| `user_id` | 可选 | `X-Virbius-User-Id` 透传 | ✅ 可选 | ✅ **若提供则写入** |
| `device_id` | 可选 | `X-Virbius-Device-Id` 透传 | ✅ 可选 | ✅ **若提供则写入** |
| `session_id` | — | — | ✅ 可选 | Post-MVP |
| `role` | — | — | ✅ | Post-MVP |
| `content` | `virbius_scan(text)` 参数 | body | ✅ | **不写入**（§9） |

Schema：[control-context.schema.json](./schemas/control-context.schema.json)

**传播规则**：

- App 在 `virbius_scan` 与 HTTP 使用**同一** `user_id` / `device_id`（与 `trace_id` 同时准备）。
- 网关 `virbius-guard`：**透传** `X-Virbius-User-Id` / `X-Virbius-Device-Id`（不用于鉴权；缺失不 400）。
- gateway-agent → engine：写入 `EvaluateRequest.user_id` / `device_id`。
- 各层写 audit 时：若请求上下文带有则**原样写入**，便于按人/设备检索。

**隐私**：`user_id` / `device_id` 属 PII，遵守租户留存与脱敏策略（DESIGN §14）。

### 4.7 引擎执行安全：`virbius-engine`（F-12）

云侧 **Groovy L3** 与 **Prompt L1** 在 `virbius-engine` 内执行。MVP 安全基线如下（实现必遵；详见 DESIGN §14.1–§14.2）。

#### 4.7.1 Groovy 沙箱（L3）

| ID | 要求 |
|----|------|
| **G1** | **禁止**引用第三方包、`@Grab`、外部 CLI/脚本；`import` 仅允许 `java.lang` 内白名单（发布期 AST 扫描，默认 **零 import**） |
| **G2** | 脚本**仅**可调用引擎注入的 **`ctx`** API（如 `enforceMode`、`riskScore`、`signals`、`inCanaryBucket`、`wouldHit`）；**禁止**任意 Java/Groovy 类 |
| **G3** | 独立 `GroovyClassLoader` + `SecureASTCustomizer`（或等价）；**禁止**访问宿主 Spring Bean、静态字段、`Binding` 逃逸 |
| **G4** | **禁止** IO、网络、子进程、反射：`java.io.*`、`java.net.*`、`ProcessBuilder`、`Runtime.exec`、`Class.forName`、`System.exit` 等 |
| **G5** | **资源上限**：单次 L3 执行 CPU 时间 ≤ **50ms**（可配置）、脚本 `body` ≤ **32KB**、调用栈深度限制 |
| **G6** | **发布门禁**：`virbius-compiler` / Registry `validating` 对 `runtime=groovy` 做 AST 扫描；不通过则 **不得** `active` |
| **G7** | MVP 脚本形态：以 §11.6.7 示意为准（enforce / canary / intent 分支），避免图灵完备自由脚本 |

**违规处理**：编译/发布失败；运行时异常 → 记 `degraded` + tenant **fail-open**（与 engine 超时策略一致）。

#### 4.7.2 Prompt 安全（L1）

| ID | 要求 |
|----|------|
| **P1** | **仅**引擎内置 **Prompt Runner** 调模型；规则 `body` 为模板字符串，**禁止**运行时 `exec`/外挂工具/第三方 SDK |
| **P2** | 用户正文 `content` 经**固定占位符**注入（如 `{{user_content}}`）；**禁止**无分隔符字符串拼接进 system 段 |
| **P3** | `content` **截断**（建议 ≤ **8KB**/请求，可配置）；system + user 模板总长遵守 Bundle 规模（DESIGN §8.2） |
| **P4** | 模型输出**仅**接受约定 **JSON**（如 `{"label":"...","score":0.0}`）；解析失败 → 不产生 block signal，走 **fail-open** 或 `review`（tenant 配置，默认 fail-open） |
| **P5** | 推理 **超时 ≤ 150ms**（§11.6.8）、**max_tokens** 上限（建议 ≤ 256） |
| **P6** | **完整 prompt 不落盘**；audit 不含 `body`（§9）；日志仅允许 hash/长度统计 |
| **P7** | 默认 **不把** `user_id`/`device_id`/Header 写入 Prompt；若运营模板显式引用须经审批 |

**说明**：「禁第三方包」是 **G1/P1 的子集**，**不能**单独作为唯一安全条款。

### 4.8 Edge manifest 同步（方案 B+，F-15）

PoC **已实现**；OpenAPI：[registry.openapi.yaml](./registry.openapi.yaml) `edge-delivery` / `admin-edge-credentials`。

**路径**（响应 **裸 JSON**，无 `ApiResult`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/edge/tenants/{tenantId}/apps/{appId}/policy-version` | 轻量版本探测 |
| GET | `/api/v1/edge/tenants/{tenantId}/apps/{appId}/manifest` | manifest body；支持 `If-None-Match` → 304 |

**policy-version 响应**：

```json
{
  "tenant_id": "default",
  "app_id": "beta",
  "artifact_revision": 3,
  "content_sha256": "<sha256 hex>",
  "published_at": "2026-05-20T10:00:00Z"
}
```

**SDK sync**：

```text
GET policy-version → 与 cache meta 比对
  → 未变：跳过
  → 已变：GET manifest + If-None-Match
  → sha256(body) == content_sha256 → 原子写 cache_dir
```

**租户级 Bearer**（`virbius.edge.delivery.auth.enabled`，默认 **false**）：

| 项 | 约定 |
|----|------|
| 凭证 | `tb_edge_tenant_credential`：`tenant_id` + `key_hash`；一租户可多 key |
| Scope | key 绑定 **tenant**；`app_id` 仅选包，不参与鉴权 |
| Header | `Authorization: Bearer` 或 `X-Virbius-Edge-Key` |
| 401 / 403 | 无效 key / tenant 路径不匹配 |
| Init | `edge_api_key` **仅**宿主 App 配置；**不**写入 manifest `sdk_config` |
| Admin | `GET|POST .../admin/tenants/{tenantId}/edge-credentials`；`POST .../{id}/revoke` |

PoC dev key（seed）：`vrb_edge_dev_default_poc_only`（`tenant=default`）。

**与 audit 分离**：manifest 内 `audit_ingest_token` → `/api/v1/internal/audit/events`；与 Edge 拉取凭证无关。

**演进**：CDN 终态见 DESIGN §8.10.2；Control 可改为返回 signed URL pointer，SDK sync 步骤不变。

---

## 5. virbius-compiler（CLI）

```bash
# edge
virbius-compiler --target=edge --tenant=default \
  --bundle-id=poc-default --bundle-version=0.1.0 \
  --registry=http://virbius-control:8080 \
  --out=./staging/default/0.1.0/

# gateway APISIX（Must）
virbius-compiler --target=gateway --gateway=apisix \
  --tenant=default --bundle-id=poc-default --bundle-version=0.1.0 \
  --out=./staging/default/0.1.0/gateway/

# gateway Kong（Stretch）
virbius-compiler --target=gateway --gateway=kong \
  --out=./staging/default/0.1.0/gateway/

# gateway OpenResty（Stretch，非 MVP 验收）
virbius-compiler --target=gateway --gateway=openresty \
  -i examples/poc-default-bundle.yaml -o staging/default/0.1.0 \
  --deploy-prefix=./data --deploy-layout=control-data
# 或：./scripts/compile-openresty-poc.sh（先 run-local.sh 写 data/gateway/）
```

详见 [openresty-gateway.md](./openresty-gateway.md)。

**输出目录（staging）**

```text
staging/{tenant}/{bundle_version}/
├── edge/          dict.bin, regex.bin, blacklist.bin, edge-manifest.json
└── gateway/       rules.lua, apisix-*.json [, kong-*.yaml] [, openresty/]
```

管侧运行时 JSON 真源（control，默认 `./data/gateway/`）见 [openresty-gateway.md](./openresty-gateway.md) §2。

**cloud 规则**：校验后写回 Registry `rule_history`，**不**生成 engine 挂载文件。

**cloud / `runtime=groovy`**：须通过 **G6** AST 扫描（§4.7.1）。  
**cloud / `runtime=prompt`**：须通过占位符与长度校验（§4.7.2 **P2–P3**）。

---

## 6. PublishOrchestrator（virbius-deploy）

### 6.1 Adapter 顺序（默认）

1. `EngineCacheSyncAdapter`  
2. `RulesArtifactAdapter`  
3. `EtcdAdapter`（APISIX Must）  
4. `KongDeckAdapter`（Stretch）  
5. `EdgeCDNAdapter`  

### 6.2 配置

```yaml
virbius:
  deploy:
    adapters:
      - engine-cache-sync
      - rules-artifact
      - etcd
      - edge-cdn
      # stretch:
      # - kong-deck
    gateway:
      enabled: [apisix]
      kong_mode: deck
```

### 6.3 sync_ack（Registry 内部）

```json
{
  "publish_id": "pub-20260520-001",
  "engine_cache": { "policy_version": "0.1.0", "cache_generation": 42 },
  "rules_artifact": { "nodes_ok": 3 },
  "gateway": { "apisix": { "etcd_revision": 12345 } },
  "edge": { "config_bus_version": "0.1.0" }
}
```

---

## 7. 管层插件配置（virbius-guard）

Route/Service 级 JSON（APISIX `plugins.virbius-guard` / Kong `config`）：

| 字段 | 类型 | 必填 | 默认 |
|------|------|------|------|
| `bundle_version` | string | ✅ | — |
| `evaluate` | bool | | `true` |
| `sse_mode` | string | | `pass-through` |
| `agent_socket` | string | | `/var/run/virbius/agent.sock` |
| `fail_mode` | string | | `open` |

**请求头**

| Header | 说明 |
|--------|------|
| `X-Virbius-Tenant` | 租户（网关注入/校验） |
| `X-Virbius-Scene` | 场景（**禁止**信任客户端伪造；网关注入） |
| `X-Virbius-Trace-Id` | 与 `ctx.trace_id` 一致（§4.5）；**缺失→网关生成**；**非法→400** |
| `X-Virbius-User-Id` | 可选；与 `ctx.user_id` 一致；网关**透传**（§4.6） |
| `X-Virbius-Device-Id` | 可选；与 `ctx.device_id` 一致；网关**透传**（§4.6） |

**Lua signal**

```lua
virbius.signal(rule_id, rule_revision, score, suggest)
-- suggest: "block" | "allow" | ...
```

**disposition**：仅认 **`effective_action`**（`allow` | `block` | `captcha` | `review`）。`review` 时 **不得** 403/428。

---

## 8. 数据模型

### 8.1 rule_history（真源，F-13）

**禁止**使用 `rule_runtime` 表作真源。运营放量写入 **`rollout_state` / `canary_percent`**（R1+）；`body` 变更写入 **`rule_history` 新 revision**（`dry_run|canary|full` 下改 body → 强制 `draft`）。

| 列 | 类型 | 说明 |
|----|------|------|
| `tenant_id` | string | 租户 |
| `rule_id` | string | **租户内唯一** |
| `rule_revision` | int | 单调递增 |
| `layer` | enum | edge / gateway / cloud |
| `runtime` | enum | lua-dsl / lua / native / prompt / groovy / cumulative / list_match（§DESIGN 8.5.0.1–2；[value-resolution.md](./value-resolution.md)，**MVP 后**） |
| `reason_code` | string | |
| `risk_score` | int | 0–100；合并时取 top 意图组内 max |
| `intent_action` | enum | allow / deny / captcha / review |
| `body` | text/json | |
| `rollout_state` | enum | **R1+** draft / disabled / dry_run / canary / full（见 [rule-rollout.md](./rule-rollout.md)） |
| `enforce_mode` | enum | **PoC 过渡** / 执行面导出：dry_run / canary / full |
| `rule_status` | enum | **PoC 过渡** draft / active / disabled |
| `canary_percent` | int? | rollout=canary 或 enforce=canary 时 |
| `effective_from` | timestamp | |
| `effective_to` | timestamp? | |
| `modified_at` | timestamp | |
| `publish_id` | string? | 关联发布批次 |

**约束**：`UNIQUE(tenant_id, rule_id, rule_revision)`；`UNIQUE(tenant_id, rule_id)` 在 `rules_current` 指针表。

### 8.2 Bundle 最小规则集（PoC）

见 [schemas/rule-bundle.schema.json](./schemas/rule-bundle.schema.json)。  
MVP 必填 rule：

| rule_id | layer | runtime |
|---------|-------|---------|
| `E0_words` | edge | lua-dsl |
| `E0_blacklist` | edge | lua-dsl |
| `gw_regex_inject` | gateway | lua |
| `cloud_l1_blacklist` | cloud | native |
| `cloud_l1_request_*` | cloud | native |
| `cloud_prompt_l1` | cloud | prompt |
| `Rule_201`–`Rule_203` | cloud | prompt |
| （可选）cloud groovy 检测脚本 | cloud | groovy |

---

## 9. 审计（MVP）

- 格式：**JSON Lines**，UTF-8，一行一事件  
- Schema：[audit-event.schema.json](./schemas/audit-event.schema.json)  
- **投递（R2a+）**：**非 allow** 各层 publish → **Redis Stream**（默认 `virbius:audit:events`）或 **Kafka**（`virbius.audit.events`）→ control **AuditIngest** → `tb_audit_events`（见 [rule-rollout.md §5.7](./rule-rollout.md)）  
- **`allow`**：各层 **仅写 `*-audit-allow.jsonl`**，不入 Stream、不入库；edge HTTP ingest 时 control 写 `audit-allow.jsonl`  
- **端（R3d）**：SDK 批量 **HTTP POST** `/api/v1/internal/audit/events`（`audit_ingest_url`）；`allow` 默认 **10% 采样**，review/block/captcha **100%**；事件可选 `sampled_allow`  
- **PoC 备档（非 allow）**：`gateway-audit.jsonl`、`engine-audit.jsonl` 等  
- **PoC 备档（allow）**：`gateway-audit-allow.jsonl`、`engine-audit-allow.jsonl`、`audit-allow.jsonl`  
- **排障**：运营台 **「审计中心」** 按 `trace_id` 合并 DB + allow 日志（§5.8）  
- **`trace_id`**：端/管/云同一交互**必须相同**（F-10）；按 `layer` 可有多行事件  

**不记录**：`bundle_id`、`bundle_version`、`skill_version`。

**端 block**：写审计时 **`trace_id` 必填**；`effective_action` 固定为 `block`，`max_risk_score` 按需填写。  
**`user_id` / `device_id`**：若 `virbius_scan_ctx` 提供，**必须**写入 audit（F-11）。  
**`trace_id_source`**：建议写入（F-10）。  
**R2a+ 推荐扩展**：`rollout_state`、`canary_percent`、`in_canary_bucket`、`degraded`、`sampled_allow`（edge allow 采样）。

---

## 9.1 门禁 G4（dry_run→canary）

`review_count_24h <= max_review_spike_ratio × max(baseline_7d_daily_avg, 10)`；基线见 [rule-rollout.md §9.2.1](./rule-rollout.md)。evaluate 响应含 `g4_pass`、`g4_skipped`、`review_spike_ratio`。

---

## 10. 发布状态机

> **Legacy（整包 Bundle）**：下列状态机用于 `POST .../bundles/{bundleId}/versions/{version}/publish` 与 `GET .../status`；OpenAPI 标注 `[Legacy]`。**日常运营**按单条规则的 **`rollout_state`** 在运营台「策略上线」完成，见 [rule-rollout.md](./rule-rollout.md)。

```text
draft → validating → eval_running → compiling → syncing → active
         ↓              ↓              ↓           ↓
       failed         failed         failed      failed
```

`syncing` 期间各 Adapter ACK 齐后 → `active`。

---

## 11. 错误码（HTTP 共用）

| code | HTTP | 说明 |
|------|------|------|
| `INVALID_ARGUMENT` | 400 | 参数/Schema 错误 |
| `NOT_FOUND` | 404 | bundle/rule 不存在 |
| `CONFLICT` | 409 | 发布中或 revision 冲突 |
| `FAILED_PRECONDITION` | 412 | 状态不允许（如非 draft 删除） |
| `INTERNAL` | 500 | 内部错误 |

---

## 12. 验收用例（摘要）

| ID | 场景 | 期望 |
|----|------|------|
| AC-01 | edge 违禁词 | `virbius_scan` BLOCK，无上行；jsonl 含 **trace_id** + rule_id + revision |
| AC-08 | trace 关联 | 同一次交互：edge audit / gateway audit / engine audit **trace_id 相同** |
| AC-09 | trace 兜底 | 缺 `X-Virbius-Trace-Id` → 网关生成并 Evaluate；**非法格式 → 400** |
| AC-09b | trace SDK | `virbius_scan` 无 `trace_id` → SDK 生成；edge block audit 含 `trace_id_source=sdk` |
| AC-10 | ControlContext | 带 `user_id`/`device_id` 时：edge/gateway/cloud audit **均含相同字段** |
| AC-11 | engine 安全 | 含 `Runtime.exec` 的 groovy **发布失败**；Prompt 非 JSON 输出 **不 block**（fail-open） |
| AC-02 | gateway 静态 block | 403；jsonl layer=gateway |
| AC-03 | engine full | 注入样本 effective_action=block |
| AC-04 | dry_run | `effective_action=review`，`max_risk_score`>0，无 403 |
| AC-05 | canary 5% | 约 5% `block`/`captcha`，其余 `review` |
| AC-06 | fail-open | engine 宕机仍转发；jsonl 记录 |
| AC-07 | 发布闭环 | Registry publish → APISIX etcd 生效，无手改 |

---

## 13. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| MVP-1.0 | 2026-05-20 | 初版；对齐 DESIGN v2.7 §11.6.0 |
| MVP-1.1 | 2026-05-20 | **F-09**：Registry + Admin → **virbius-control** 单进程、统一 HTTP |
| MVP-1.2 | 2026-05-20 | **F-10**：`trace_id` 全局唯一；扩展 `virbius_scan_ctx`；端 block 审计必填 |
| MVP-1.3 | 2026-05-20 | **F-10 Strict**：网关缺失/非法 `X-Virbius-Trace-Id` → **400**（不再生成兜底） |
| MVP-1.4 | 2026-05-20 | **F-11**：`ControlContext` 公用；`user_id`/`device_id` 透传与审计可选字段 |
| MVP-1.5 | 2026-05-20 | **F-12**：Groovy 沙箱 G1–G7、Prompt 安全 P1–P7（§4.7） |
| MVP-1.6 | 2026-05-20 | **F-10 修订**：缺 Header 管侧生成；非法仍 400；SDK 自动生成；`trace_id_source` |
| MVP-1.8 | 2026-05-20 | **F-14** gateway-agent 由 Go 改为 **Rust**（与 `virbius-core` 统一） |
| MVP-1.7 | 2026-05-20 | **F-13** `rule_history` 术语；**F-14** gateway-agent Go；[POC-REPO.md](../POC-REPO.md) |
| MVP-1.10 | 2026-05-20 | **intent_action** + ActionMerge；对外扁平响应（`max_risk_score`）；移除 `would_block` / 全量 `signals[]` |
| MVP-1.11 | 2026-05-20 | **allow 不入库**（`*-audit-allow.jsonl`）；运营台 **审计中心**；`GET .../audit/trace/{traceId}` |
