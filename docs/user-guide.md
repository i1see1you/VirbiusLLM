# Virbius 用户使用文档（客户端集成）

本文面向 **App / 服务端 / Rust 客户端** 集成方，说明如何在业务中接入 Virbius 大模型防控能力。运营配置规则请使用 Admin 运营台（`ops.html`）及 [POC-SEED-API.md](./POC-SEED-API.md)；本文聚焦 **运行时集成**。

**相关文档**

| 文档 | 用途 |
|------|------|
| [DESIGN.md](./DESIGN.md) | 架构与设计 |
| [MVP-OPENSPEC §4](./openspec/MVP-OPENSPEC.md) | SDK / Header / 审计契约 |
| [rule-rollout.md](./openspec/rule-rollout.md) | 策略上线与放量 |
| [POC-SEED-API.md](./POC-SEED-API.md) | 本地 PoC 与 curl 示例 |

---

## 1. 架构概览

Virbius 采用 **端 - 管 - 云** 分层执行，各层可 **单独或组合** 集成：

```text
用户输入
   │
   ▼
┌─────────────────┐
│ ① 端 L0 SDK     │  virbius-core（Rust / C ABI）
│   scan + DLP    │  本地同步，毫秒级
└────────┬────────┘
         │ HTTP + Virbius Header
         ▼
┌─────────────────┐
│ ② 管 网关       │  APISIX/Kong + virbius-guard
│   Lua 规则      │  必经流量，十～数百 ms
└────────┬────────┘
         │ gateway-agent
         ▼
┌─────────────────┐
│ ③ 云 engine     │  Prompt L1 + Groovy L3
│   策略合并      │  按需 Evaluate
└────────┬────────┘
         ▼
      大模型 API
```

| 层 | 集成方式 | 当前 Rust 支持 |
|----|----------|----------------|
| **端** | 嵌入 `virbius-core` | ✅ 已提供 |
| **管** | 请求走 Virbius 网关 URL | ✅ HTTP Header（无独立 crate） |
| **云** | 由网关 agent 调 engine | 客户端不直连（规划中 `virbius-client`） |

**推荐组合**

| 场景 | 建议 |
|------|------|
| 移动端 / 桌面端，要低延迟本地拦截 | **仅端** 或 **端 + 管** |
| Web / API，无法改 App 二进制 | **仅管（± 云）** |
| 高合规、防 bypass | **端 + 管 + 云** |

---

## 2. 规则从哪里来

1. 运营在 **virbius-control** 运营台配置规则（按 layer：`edge` / `gateway` / `cloud`）。
2. 规则需 **上线** 至 `dry_run` / `canary` / `full` 才会进入执行面（`draft` 不生效）。
3. 发布产物：
   - **端**：`edge-manifest.json`（含 `rules[]` 关键词、`dlp_rules[]` 脱敏）
   - **管**：网关 `rules.lua` 等（由 Compiler 推送 etcd）
   - **云**：写入 Registry，engine **RuleCache** 热加载

Per-App 端 manifest 路径（方案 A）：

```text
./data/edge/{tenant_id}/{app_id}/edge-manifest.json
```

---

## 3. 端侧 SDK（virbius-core）

### 3.1 添加依赖

```toml
[dependencies]
virbius-core = { path = "../VirbiusLLM/virbius-core" }
```

### 3.2 初始化（生产）

宿主 App 从自有配置构造 [`EdgeInitConfig`](../../virbius-core/src/sync.rs)，**不要依赖环境变量**：

```rust
use std::path::PathBuf;
use virbius_core::{EdgeInitConfig, ScanContext, VirbiusEdge};

let edge = VirbiusEdge::init(EdgeInitConfig {
    control_base_url: Some("http://127.0.0.1:8080".into()),
    tenant_id: "default".into(),
    app_id: "beta".into(),
    edge_api_key: Some("vrb_edge_...".into()), // Control 开启 VIRBIUS_EDGE_AUTH_ENABLED 时必填
    cache_dir: PathBuf::from(app_cache_dir),
    offline_manifest_path: None,
})?;
```

| 字段 | 说明 |
|------|------|
| `control_base_url` | Control 根 URL；与 `app_id` 同设时启动/`reload` 走方案 B 同步 |
| `tenant_id` | 租户，默认 `default` |
| `app_id` | App 标识；与 manifest 内 `app_id` 校验 |
| `edge_api_key` | Control Edge 拉取 Bearer（租户级；与 `tenant_id` scope 一致；**不在 manifest 内**） |
| `cache_dir` | manifest 与 `meta.json` 缓存目录（**必填**） |
| `offline_manifest_path` | 离线/调试：本地 manifest 路径（设则跳过 Control 同步） |

C ABI：`virbius_init_config_json("{...}")`，JSON 字段同上（见 `include/virbius.h`）。

规则与审计等行为由 Control 下发的 manifest `sdk_config` 决定；`reload()` 会再次 sync + 热更新。

**示例 / CI 专用**：`VirbiusEdge::new_from_env()` 或 `EdgeInitConfig::from_env()` 读取 `VIRBIUS_*` 环境变量，**不要在生产 App 中使用**。

### 3.2.1 Control Edge 鉴权（方案 B+）

Control 可通过 `VIRBIUS_EDGE_AUTH_ENABLED=true` 开启租户级 Bearer（默认关闭，与 PoC 兼容）：

| 项 | 说明 |
|----|------|
| Header | `Authorization: Bearer <edge_api_key>` 或 `X-Virbius-Edge-Key` |
| Scope | key 绑定 **tenant_id**；path 中 `{tenantId}` 须一致，否则 403 |
| PoC dev key | `vrb_edge_dev_default_poc_only`（seed，`tenant=default`） |
| 签发 | `POST /api/v1/admin/tenants/{tenantId}/edge-credentials`（响应含一次性 `api_key`） |
| 吊销 | `POST .../edge-credentials/{credentialId}/revoke` |

`edge_api_key` **仅**在 Init 配置，不下发到 manifest（与 `audit_ingest_token` 分离）。

### 3.2.2 示例 / CI 环境变量（`from_env`）

以下变量供 **`VirbiusEdge::new_from_env()`**、示例与自动化测试使用；**生产 App 应写入自有配置**，见 §3.2。

| 变量 | 说明 |
|------|------|
| `VIRBIUS_CONTROL_BASE_URL` | Control 根 URL；与 `app_id` 同设时启用远程 sync |
| `VIRBIUS_TENANT_ID` | 租户，默认 `default` |
| `VIRBIUS_APP_ID` | 拉取的 per-app manifest 标识 |
| `VIRBIUS_EDGE_CACHE_DIR` | 本地 `edge-manifest.json` + `meta.json` 目录 |
| `VIRBIUS_EDGE_API_KEY` | Bearer；Control `VIRBIUS_EDGE_AUTH_ENABLED=true` 时必填 |
| `VIRBIUS_EDGE_MANIFEST_PATH` | 离线 manifest 路径；**设则跳过 Control 同步** |
| `VIRBIUS_DATA_DIR` | 未设 `VIRBIUS_EDGE_CACHE_DIR` 时，cache 默认为 `{DATA_DIR}/edge/{tenant}/{app}` |

**Control 侧**（启动 virbius-control 时）：

| 变量 | 说明 |
|------|------|
| `VIRBIUS_EDGE_AUTH_ENABLED` | `true` 开启 Edge 拉取 Bearer（默认 `false`） |
| `VIRBIUS_DATA_DIR` | 产物与 SQLite 目录，默认 `./data` |

PoC 联调示例见 [POC-SEED-API.md §6.5](./POC-SEED-API.md)。

### 3.3 初始化（示例）

```rust
use virbius_core::VirbiusEdge;

let edge = VirbiusEdge::new(); // 仅本地空规则或 new_from_env；生产请用 init()
```

### 3.4 热更新

```rust
edge.reload();
```

### 3.5 请求上下文 `ScanContext`

与网关 Header、审计字段对齐（[ControlContext](./openspec/schemas/control-context.schema.json)）：

| 字段 | 说明 |
|------|------|
| `user_id` | 可选，PII |
| `device_id` | 可选；canary 分桶常用 |
| `scene` | 可选，默认 `default` |
| `trace_id` | 可选；**不传则 SDK 生成 UUID v4**；传入须为 **UUID v4**（36 字符） |

```rust
use virbius_core::ScanContext;

let ctx = ScanContext {
    user_id: Some("u-123".into()),
    device_id: Some("dev-abc".into()),
    scene: Some("chat".into()),
    trace_id: None,
};
```

### 3.5 内容防控 `scan`

对用户 **输入**（prompt）做本地关键词检测：

```rust
use virbius_core::{EffectiveAction, ScanContext, VirbiusEdge, VirbiusError};

fn check_input(edge: &VirbiusEdge, ctx: ScanContext, text: &str) -> Result<(), VirbiusError> {
    let out = edge.scan_with(ctx, text)?;

    match out.action {
        EffectiveAction::Allow => {
            // 放行；out.trace_id 用于后续 HTTP Header
        }
        EffectiveAction::Block => {
            // full/canary 生效，本地拦截，勿调 LLM
            // out.primary 含 rule_id / reason_code
        }
        EffectiveAction::Review => {
            // dry_run 命中：可放行，应记审计
        }
        EffectiveAction::Captcha => {
            // 触发验证码
        }
    }
    Ok(())
}
```

| `EffectiveAction` | 含义 |
|-------------------|------|
| `Allow` | 未命中或未强制执行 |
| `Block` | 须拦截 |
| `Review` | 观测命中（dry_run） |
| `Captcha` | 验证码 |

**错误**

| `VirbiusError` | 原因 |
|----------------|------|
| `EmptyContent` | 空字符串 |
| `InvalidTraceId` | 自定义 trace_id 格式非法 |

### 3.6 DLP 脱敏（仅端层）

DLP 规则在运营台以 **`dlp-dsl`** 配置，写入 manifest 的 **`dlp_rules[]`**（与关键词 `rules[]` 分离）。`intent_action` 固定 **`allow`**，不参与 scan 的 ActionMerge。

**流程**：上云前脱敏 → 调 LLM → 回包后回填。

```rust
// ① 与 scan 共用 trace_id
let scan = edge.scan_with(ctx.clone(), user_text)?;
let trace_id = scan.trace_id;

// ② 脱敏（dry_run 只检测不替换；full/canary 替换占位符）
let masked = edge.desensitize_in_with(ctx.clone(), user_text)?;
let to_cloud = masked.text; // 发给大模型

// ③ 模型返回后回填
let restored = edge.desensitize_out_with(&trace_id, &model_reply, ctx);
let to_user = restored.text;
```

占位符示例：`{{VIRBIUS_PHONE_CN_0}}`。明文暂存于进程内 TokenVault，TTL 见 `sdk_config.dlp_vault_ttl_ms`（默认 30 分钟）。

**实体类型**：`phone_cn`、`idcard_cn`、`email`、`bank_card_cn`、`custom_regex`。

内置实体使用 **ASCII 数字/邮箱字符边界**（非 `\b`），中文紧贴数字或邮箱时仍可匹配；长串纯数字内部仍不会误切 11 位手机号。

### 3.7 C ABI（非 Rust 语言）

头文件：[virbius-core/include/virbius.h](../virbius-core/include/virbius.h)

| 函数 | 说明 |
|------|------|
| `virbius_init` | 加载 manifest |
| `virbius_scan` | 同步 scan |
| `virbius_reload` | 热更新 |
| `virbius_free_string` | 释放 scan 返回的 C 字符串 |

### 3.8 端侧示例

```bash
cd virbius-core
cargo run --example rust_client_demo
```

---

## 4. 经网关调用 LLM（管层 ± 云）

客户端 **不嵌入** 管/云 SDK，将 LLM 请求发往 **已挂载 virbius-guard 的网关入口**（OpenAI Chat Completions 兼容路径，如 `/v1/chat/completions`）。

### 4.1 推荐流程（端 + 管）

```text
1. virbius-core scan → Block 则结束
2. 取 scan.trace_id
3. HTTP POST 网关，带 Virbius Header
4. 网关 evaluate（管 Lua + 可选云 engine）→ 403 或转发 upstream
```

### 4.2 HTTP Header

| Header | 设置方 | 说明 |
|--------|--------|------|
| `X-Virbius-Trace-Id` | **客户端** | 与端 scan 的 `trace_id` 一致；**UUID v4** |
| `X-Virbius-User-Id` | 客户端（可选） | 与 `ScanContext.user_id` 一致 |
| `X-Virbius-Device-Id` | 客户端（可选） | 与 `ScanContext.device_id` 一致 |
| `X-Virbius-Edge-Pass` | 客户端（可选） | 端已 scan 且放行时可设 `1` |
| `X-Virbius-Tenant` | **网关注入** | 勿客户端伪造 |
| `X-Virbius-Scene` | **网关注入** | 勿客户端伪造；按路由/App 映射 |

### 4.3 请求体

网关从 OpenAI 形态 JSON 的 **`messages[].content`** 抽取 user 文本做 evaluate，例如：

```json
{
  "model": "gpt-4",
  "messages": [
    { "role": "user", "content": "用户 prompt" }
  ]
}
```

### 4.4 Rust 示例（ureq）

```rust
use serde_json::json;

let trace_id = scan.trace_id; // 来自 edge.scan_with

let response = ureq::post("http://127.0.0.1:9080/v1/chat/completions")
    .set("Content-Type", "application/json")
    .set("X-Virbius-Trace-Id", &trace_id)
    .set("X-Virbius-User-Id", "demo-user")
    .set("X-Virbius-Device-Id", "demo-device")
    .set("X-Virbius-Edge-Pass", "1")
    .send_json(json!({
        "model": "gpt-4",
        "messages": [{ "role": "user", "content": user_message }]
    }))?;

match response.status() {
    403 | 428 => { /* 网关拦截 */ }
    400 => { /* 常见：非法 X-Virbius-Trace-Id */ }
    200..=299 => { /* 正常 LLM 响应 */ }
    _ => {}
}
```

### 4.5 仅管层（无端 SDK）

自行生成 UUID v4 作为 `X-Virbius-Trace-Id`，直接 POST 网关即可；网关/agent 负责后续 evaluate。

### 4.6 网关示例

```bash
export VIRBIUS_GATEWAY_URL=http://127.0.0.1:9080/v1/chat/completions
cd virbius-core
cargo run --example gateway_http_client

# 跳过端 scan，仅测管层：
export VIRBIUS_SKIP_EDGE=1
cargo run --example gateway_http_client
```

PoC curl 见 [POC-SEED-API.md §7](./POC-SEED-API.md)。

### 4.7 管侧 JSON 与网关路径（APISIX / OpenResty）

**真源**：virbius-control `ArtifactService` 写入 `{VIRBIUS_DATA_DIR}/gateway/`（默认 `./data/gateway/`），**不经过 Redis**。

| 文件 | 用途 |
|------|------|
| `default-access-lists.json` | `context_bindings`（如 `X-App-Id`→`app_id`）、管侧名单 |
| `default-scene-registry.json` | 运行时 `(app_id, uri, match) → scene_id` |

启动 `./scripts/run-local.sh` 后会自动 refresh。改名单后 control 同步即可；OpenResty 在 **方案 A** 下无需重新 compile。

| 网关 | 路径配置位置 |
|------|----------------|
| APISIX | `plugins.virbius-guard.lists_file` / `scene_registry_file`（service JSON） |
| OpenResty（Stretch） | compiler 生成的 `effective-*.json` 内同名字段 |

OpenResty 本地对齐：`./scripts/compile-openresty-poc.sh`（`--deploy-layout=control-data`）。详见 [openspec/openresty-gateway.md](./openspec/openresty-gateway.md)。

### 4.8 HTTP 状态码

| 状态 | 含义 |
|------|------|
| 200 | 放行（dry_run 下 review 仍可能 200） |
| 403 | 终局 block |
| 400 | 如 trace_id 格式非法 |

---

## 5. trace_id 与审计串联

全链路排障依赖同一 **`trace_id`**：

```text
端 scan 生成/传入 trace_id
  → X-Virbius-Trace-Id 送网关
    → gateway-agent / engine evaluate
      → 各层 audit 事件
```

| 场景 | 要求 |
|------|------|
| 端 **Block** 不上行 | 须在端生成 trace 并写 edge audit |
| 仅管层 | 客户端传合法 trace，或依赖网关策略（见 MVP 版本说明） |
| 格式 | **UUID v4**，固定 36 字符（如 `uuidgen` 小写） |

**运营台排障**：http://127.0.0.1:8080/ui → **审计中心**，输入 `trace_id` 可查看 `tb_audit_events`（review/block/captcha）与各层 **allow JSONL** 合并结果；API：`GET /api/v1/admin/tenants/{tenant}/audit/trace/{traceId}`（详见 [rule-rollout.md §5.8](./openspec/rule-rollout.md)）。

---

## 6. 放量状态与客户端行为

规则 `rollout_state` 影响 **是否强制执行**（由 manifest / 网关导出快照决定）：

| 状态 | 客户端可见行为 |
|------|----------------|
| `draft` / `disabled` | 不进入执行面 |
| `dry_run` | 命中 → 多为 `Review`，**不 Block** |
| `canary` | 按 `device_id` 等分桶强制执行 |
| `full` | 命中 → `Block`（端）或 403（管） |

DLP：`dry_run` 仅检测；`full`/`canary` 才替换占位符并写 Vault。

---

## 7. 完整集成示例（端 + DLP + 网关）

```rust
async fn chat(
    edge: &VirbiusEdge,
    gateway_url: &str,
    ctx: ScanContext,
    user_text: &str,
) -> Result<String, Box<dyn std::error::Error>> {
    // 1. 端 scan
    let scan = edge.scan_with(ctx.clone(), user_text)?;
    if scan.action == virbius_core::EffectiveAction::Block {
        return Ok("内容未通过本地安全检测".into());
    }
    let trace_id = scan.trace_id;

    // 2. DLP 脱敏后上云
    let masked = edge.desensitize_in_with(ctx.clone(), user_text)?;

    // 3. 经网关调 LLM（示例用 ureq；生产可用 reqwest）
    let resp = ureq::post(gateway_url)
        .set("Content-Type", "application/json")
        .set("X-Virbius-Trace-Id", &trace_id)
        .set("X-Virbius-Edge-Pass", "1")
        .send_json(serde_json::json!({
            "model": "gpt-4",
            "messages": [{ "role": "user", "content": masked.text }]
        }))?;

    if resp.status() == 403 {
        return Ok("请求被网关拦截".into());
    }
    let model_reply = resp.into_string()?;

    // 4. DLP 回填（若响应中含占位符）
    let restored = edge.desensitize_out_with(&trace_id, &model_reply, ctx);
    Ok(restored.text)
}
```

---

## 8. 故障排查

| 现象 | 可能原因 |
|------|----------|
| scan 始终 Allow | manifest 路径错误；规则仍在 `draft` |
| DLP 不替换 | 规则 `dry_run`；或 canary 未命中；或手机号无词边界 |
| 网关 400 | `X-Virbius-Trace-Id` 非法 |
| 网关 403 | 管/云规则 full/canary 拦截 |
| 云规则不生效 | 未走 gateway evaluate；或 engine 未同步 RuleCache |
| 端 manifest app_id 不匹配 | Init `app_id` 与 manifest 内 `app_id` 不一致，规则被清空 |
| 端 manifest sync 401/403 | Control 开启鉴权但未配 `edge_api_key`；或 key 与 `tenant_id` 不一致 |
| 端 manifest sync 404 | 未 publish edge 规则，或 `app_id` 无 artifact；检查 `data/edge/{tenant}/{app}/` |

---

## 9. 示例与代码位置

| 示例 | 命令 | 说明 |
|------|------|------|
| 端 scan + DLP | `cargo run --example rust_client_demo` | 内置 fixture manifest |
| 端 + 网关 HTTP | `cargo run --example gateway_http_client` | 需本地 APISIX PoC |
| Fixture | `virbius-core/examples/fixtures/demo-edge-manifest.json` | 本地调试 manifest |

---

## 10. 版本与契约

- SDK crate：`virbius-core` 0.1.x
- Manifest：`manifest_version: "1"`，见 [edge-manifest.schema.json](./openspec/schemas/edge-manifest.schema.json)
- 接口冻结说明：[MVP-OPENSPEC.md](./openspec/MVP-OPENSPEC.md)

如有集成问题，可先对照 `cargo run --example rust_client_demo` 与 [POC-SEED-API.md](./POC-SEED-API.md) 中的 curl 行为是否一致。
