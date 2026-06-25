# Virbius User Guide (Client Integration)

**中文:** [用户使用手册](./user-guide.md)

This guide is for **app, server, and Rust client** integrators. It covers runtime integration with Virbius LLM security. For rule configuration, use the admin console (`ops.html`) and [seed-api.md](./seed-api.md).

**Related docs**

| Doc | Purpose |
|-----|---------|
| [DESIGN.md](./DESIGN.md) | Architecture and design |
| [seed-api.md](./seed-api.md) | Local PoC, admin APIs, curl examples |
| [repo-layout.md](./repo-layout.md) | Repo layout and local startup |

---

## 1. Architecture overview

Virbius uses **edge – gateway – cloud** layered enforcement. You can integrate one or more layers:

```text
User input
   │
   ▼
┌─────────────────┐
│ ① Edge L0 SDK   │  virbius-core (Rust / C ABI)
│   scan + DLP    │  local sync, millisecond latency
└────────┬────────┘
         │ HTTP + Virbius headers
         ▼
┌─────────────────┐
│ ② Gateway       │  APISIX/Kong + virbius-guard
│   Lua rules     │  tens–hundreds of ms
└────────┬────────┘
         │ gateway-agent
         ▼
┌─────────────────┐
│ ③ Cloud engine  │  Prompt L1 + Groovy L3
│   policy merge  │  on-demand Evaluate
└────────┬────────┘
         ▼
      LLM API
```

| Layer | Integration | Rust support |
|-------|-------------|--------------|
| **Edge** | Embed `virbius-core` | ✅ Available |
| **Gateway** | Send LLM traffic through Virbius gateway URL | ✅ HTTP headers (no dedicated crate) |
| **Cloud** | Invoked by gateway-agent → engine | Clients do not call engine directly |

**Recommended combinations**

| Scenario | Suggestion |
|----------|------------|
| Mobile/desktop, low-latency local block | **Edge only** or **edge + gateway** |
| Web/API, cannot ship SDK in app binary | **Gateway (± cloud)** only |
| High compliance, anti-bypass | **Edge + gateway + cloud** |

---

## 2. Where rules come from

1. Operators configure rules in **virbius-control** (layers: `edge` / `gateway` / `cloud`).
2. Rules must be rolled out to `dry_run` / `canary` / `full` to enter the execution plane (`draft` does not enforce).
3. Published artifacts:
   - **Edge:** `edge-manifest.json` (keywords, `dlp_rules[]`)
   - **Gateway:** access-lists / scene-registry JSON (via control publish + sidecar sync)
   - **Cloud:** Registry DB → engine **RuleCache** hot reload

Per-app edge manifest path (scheme B):

```text
./data/edge/{tenant_id}/{app_id}/edge-manifest.json
```

---

## 3. Edge SDK (virbius-core)

### 3.1 Add dependency

```toml
[dependencies]
virbius-core = { path = "../VirbiusLLM/virbius-core" }
```

### 3.2 Production initialization

Build [`EdgeInitConfig`](../../virbius-core/src/sync.rs) from your app config. **Do not rely on environment variables in production:**

```rust
use std::path::PathBuf;
use virbius_core::{EdgeInitConfig, ScanContext, VirbiusEdge};

let edge = VirbiusEdge::init(EdgeInitConfig {
    control_base_url: Some("http://127.0.0.1:8080".into()),
    tenant_id: "default".into(),
    app_id: "beta".into(),
    edge_api_key: Some("<your_api_key>".into()), // required when VIRBIUS_API_KEY_AUTH_ENABLED=true
    cache_dir: PathBuf::from(app_cache_dir),
    offline_manifest_path: None,
})?;
```

| Field | Description |
|-------|-------------|
| `control_base_url` | Control base URL; with `app_id`, enables scheme B sync on init/reload |
| `tenant_id` | Tenant ID, default `default` |
| `app_id` | App ID; must match manifest `app_id` |
| `edge_api_key` | Bearer for Edge pull APIs (tenant-scoped; not embedded in manifest) |
| `cache_dir` | Cache dir for manifest + `meta.json` (**required**) |
| `offline_manifest_path` | Offline/debug local manifest (skips Control sync when set) |

C ABI: `virbius_init_config_json("{...}")` with the same JSON fields (see `include/virbius.h`).

Behavior (audit, etc.) follows manifest `sdk_config`. `reload()` re-syncs and hot-reloads rules.

**Examples / CI only:** `VirbiusEdge::new_from_env()` — **not for production apps**.

### 3.3 API key auth (scheme B+)

When `VIRBIUS_API_KEY_AUTH_ENABLED=true` on control:

| Item | Description |
|------|-------------|
| Header | `Authorization: Bearer <api_key>` or `X-Virbius-Api-Key` |
| Scope | Key bound to **tenant_id**; path `{tenantId}` must match (`platform_admin` `*` excepted) |
| Edge pull | Requires `tenant_viewer+`; see [seed-api.md §6.5](./seed-api.md) for key management |
| Admin writes | Requires `tenant_admin+`; see [seed-api.md §6.5](./seed-api.md) |
| Issue key | `POST /api/v1/admin/tenants/{tenantId}/api-credentials` |

### 3.4 Example / CI environment variables

The following variables are used by `VirbiusEdge::new_from_env()`, examples, and automation tests. **Production apps should read from their own config**, see §3.2.

| Variable | Description |
|----------|-------------|
| `VIRBIUS_CONTROL_BASE_URL` | Control base URL; enables remote sync when `app_id` is also set |
| `VIRBIUS_TENANT_ID` | Tenant, default `default` |
| `VIRBIUS_APP_ID` | Per-app manifest identifier for pull |
| `VIRBIUS_EDGE_CACHE_DIR` | Local manifest + `meta.json` directory |
| `VIRBIUS_EDGE_API_KEY` | Bearer when control `VIRBIUS_API_KEY_AUTH_ENABLED=true` (use `tenant_viewer` key) |
| `VIRBIUS_EDGE_MANIFEST_PATH` | Offline manifest path; skips Control sync when set |
| `VIRBIUS_DATA_DIR` | When `EDGE_CACHE_DIR` unset, cache defaults to `{DATA_DIR}/edge/{tenant}/{app}` |

**Control side** (when starting virbius-control):

| Variable | Description |
|----------|-------------|
| `VIRBIUS_API_KEY_AUTH_ENABLED` | `true` enables Admin / Edge / tenants API Bearer (default `false`) |
| `VIRBIUS_DATA_DIR` | Artifact and SQLite directory, default `./data` |

### 3.5 Hot reload

```rust
edge.reload();
```

### 3.6 Request context `ScanContext`

Aligned with gateway headers and audit fields (see [DESIGN.md](./DESIGN.md) §8):

| Field | Description |
|-------|-------------|
| `user_id` | Optional, PII |
| `device_id` | Optional; used for canary bucketing |
| `scene` | Optional, default `default` |
| `trace_id` | Optional; **UUID v4** if omitted |

### 3.7 Content scan

Local keyword detection on user **input** (prompt):

```rust
let out = edge.scan_with(ctx, text)?;
match out.action {
    EffectiveAction::Allow => { /* proceed */ }
    EffectiveAction::Block => { /* block locally, do not call LLM */ }
    EffectiveAction::Review => { /* dry_run hit; audit, may still proceed */ }
    EffectiveAction::Captcha => { /* captcha */ }
}
```

### 3.8 DLP desensitization (edge only)

Configure **`dlp-dsl`** rules in the admin console. They are written to manifest `dlp_rules[]` (separate from keyword `rules[]`). `intent_action` is fixed to `allow`; DLP does not participate in `scan` ActionMerge.

**Flow**: desensitize before LLM → call LLM → restore placeholders in response.

```rust
// ① Share trace_id with scan
let scan = edge.scan_with(ctx.clone(), user_text)?;
let trace_id = scan.trace_id;

// ② Desensitize (dry_run only detects; full/canary replaces with placeholders)
let masked = edge.desensitize_in_with(ctx.clone(), user_text)?;
let to_cloud = masked.text; // send to LLM

// ③ Restore after model reply
let restored = edge.desensitize_out_with(&trace_id, &model_reply, ctx);
let to_user = restored.text;
```

Placeholders look like `{{VIRBIUS_PHONE_CN_0}}`. Plaintext is held in an in-process TokenVault with TTL from `sdk_config.dlp_vault_ttl_ms` (default 30 minutes).

**Entity types**: `phone_cn`, `idcard_cn`, `email`, `bank_card_cn`, `custom_regex`.

Built-in entities use ASCII digit/email character boundaries (not `\b`), so Chinese characters adjacent to digits or email addresses are still matched correctly; long digit strings are not incorrectly segmented as 11-digit phone numbers.

### 3.9 C ABI (non-Rust languages)

Header: [virbius-core/include/virbius.h](../virbius-core/include/virbius.h)

| Function | Description |
|----------|-------------|
| `virbius_init` | Load manifest |
| `virbius_scan` | Synchronous scan |
| `virbius_reload` | Hot reload |
| `virbius_free_string` | Free C string returned by scan |

### 3.10 Edge example

```bash
cd virbius-core
cargo run --example rust_client_demo
```

---

## 4. LLM calls via gateway

Clients do **not** embed gateway/cloud SDKs. POST to a **virbius-guard** gateway entry (OpenAI-compatible path, e.g. `/v1/chat/completions`).

### 4.1 Recommended flow (edge + gateway)

```text
1. virbius-core scan → stop on Block
2. Use scan.trace_id
3. HTTP POST to gateway with Virbius headers
4. Gateway evaluate (Lua ± cloud engine) → 403 or forward upstream
```

### 4.2 HTTP headers

| Header | Set by | Description |
|--------|--------|-------------|
| `X-Virbius-Trace-Id` | **Client** | Same as edge `trace_id`; **UUID v4** |
| `X-Virbius-User-Id` | Client (optional) | Matches `ScanContext.user_id` |
| `X-Virbius-Device-Id` | Client (optional) | Matches `ScanContext.device_id` |
| `X-Virbius-Edge-Pass` | Client (optional) | Set `1` when edge scan passed |
| `X-Virbius-Tenant` | **Gateway** | Do not forge from client |
| `X-Virbius-Scene` | **Gateway** | Do not forge from client |

### 4.3 Request body

Gateway extracts user text from OpenAI-style `messages[].content`:

```json
{
  "model": "gpt-4",
  "messages": [
    { "role": "user", "content": "user prompt" }
  ]
}
```

### 4.4 Rust example (ureq)

```rust
let response = ureq::post("http://127.0.0.1:9080/v1/chat/completions")
    .set("Content-Type", "application/json")
    .set("X-Virbius-Trace-Id", &trace_id)
    .set("X-Virbius-User-Id", "demo-user")
    .set("X-Virbius-Edge-Pass", "1")
    .send_json(json!({
        "model": "gpt-4",
        "messages": [{ "role": "user", "content": user_message }]
    }))?;
```

### 4.5 Gateway-only (no edge SDK)

Generate a UUID v4 as `X-Virbius-Trace-Id` and POST to the gateway directly.

### 4.6 Gateway runtime JSON (APISIX / OpenResty)

**Runtime policy files** are published by control and synced locally by the sidecar:

```text
control refreshArtifacts
  → Redis blob + pointer (default: no local write on control host)
  → virbius-gateway-sync poll (default 5s in run-local.sh)
  → {VIRBIUS_GATEWAY_CACHE_DIR}/{tenant}-access-lists.json
  → {VIRBIUS_GATEWAY_CACHE_DIR}/{tenant}-scene-registry.json
```

Default cache dir: `./data/gateway/`. APISIX / OpenResty `lists_file` and `scene_registry_file` must point there. Lua `file_cache` reloads on **file mtime** — **no nginx reload** needed for list/scene changes.

Sidecar may also pull via Control: `GET /api/v1/gateway/tenants/{tenantId}/policy-version` and `/snapshot` (Bearer with Edge/Viewer role).

| File | Purpose |
|------|---------|
| `{tenant}-access-lists.json` | `context_bindings`, `lists[]`, `cumulatives[]`, etc. |
| `{tenant}-scene-registry.json` | Route → `scene_id` mapping |

**Refresh triggers:** control startup, access-list changes, `sync-rules`, rule rollout entering/leaving execution plane, Admin `gateway-artifacts/refresh`.

**Nginx shell config** (`locations.conf`, `effective-*.json`) comes from `virbius-compiler`; recompile only when routes/upstreams change:

```bash
./scripts/run-local.sh
./scripts/compile-openresty-poc.sh
```

### 4.7 HTTP status codes

| Status | Meaning |
|--------|---------|
| 200 | Allowed (dry_run review may still return 200) |
| 403 | Final block |
| 400 | e.g. invalid `X-Virbius-Trace-Id` |

---

## 5. trace_id and audit

Use one **`trace_id`** end to end:

```text
Edge scan → X-Virbius-Trace-Id → gateway-agent / engine → audit events
```

| Scenario | Requirement |
|----------|-------------|
| Edge **Block**, no upstream call | Generate trace on edge and write edge audit |
| Gateway-only | Client sends valid UUID v4 trace |
| Format | **UUID v4**, 36 characters |

**Admin troubleshooting:** http://127.0.0.1:8080/ui → Audit center, or `GET /api/v1/admin/tenants/{tenant}/audit/trace/{traceId}`.

---

## 6. Rollout states and client behavior

| State | Client-visible behavior |
|-------|-------------------------|
| `draft` / `disabled` | Not in execution plane |
| `dry_run` | Hit → usually `Review`, **not Block** |
| `canary` | Enforced by bucket (e.g. `device_id`) |
| `full` | Hit → `Block` (edge) or 403 (gateway) |

DLP: `dry_run` detects only; `full`/`canary` replace placeholders and use Vault.

---

## 7. Full example (edge + DLP + gateway)

```rust
async fn chat(
    edge: &VirbiusEdge,
    gateway_url: &str,
    ctx: ScanContext,
    user_text: &str,
) -> Result<String, Box<dyn std::error::Error>> {
    // 1. Edge scan
    let scan = edge.scan_with(ctx.clone(), user_text)?;
    if scan.action == virbius_core::EffectiveAction::Block {
        return Ok("Content blocked by local security check".into());
    }
    let trace_id = scan.trace_id;

    // 2. DLP desensitize before sending to LLM
    let masked = edge.desensitize_in_with(ctx.clone(), user_text)?;

    // 3. Call LLM via gateway (use reqwest in production)
    let resp = ureq::post(gateway_url)
        .set("Content-Type", "application/json")
        .set("X-Virbius-Trace-Id", &trace_id)
        .set("X-Virbius-Edge-Pass", "1")
        .send_json(serde_json::json!({
            "model": "gpt-4",
            "messages": [{ "role": "user", "content": masked.text }]
        }))?;

    if resp.status() == 403 {
        return Ok("Request blocked by gateway".into());
    }
    let model_reply = resp.into_string()?;

    // 4. DLP restore placeholders in response
    let restored = edge.desensitize_out_with(&trace_id, &model_reply, ctx);
    Ok(restored.text)
}
```

---

## 8. Troubleshooting

| Symptom | Possible cause |
|---------|----------------|
| scan always Allow | Wrong manifest path; rules still `draft` |
| DLP no replacement | Rule in `dry_run`; canary miss; no word boundary for phone number |
| Gateway 400 | Invalid `X-Virbius-Trace-Id` |
| Gateway 403 | Gateway/cloud full/canary block |
| Cloud rules not applied | Not routed through gateway evaluate; engine RuleCache not synced |
| Edge manifest app_id mismatch | Init `app_id` differs from manifest `app_id`; rules cleared |
| Edge sync 401/403 | Auth enabled but no `edge_api_key`; or key `tenant_id` mismatch |
| Edge sync 404 | No edge artifact for `app_id`; check `data/edge/{tenant}/{app}/` |
| Gateway lists stale | Sidecar not running; check `gateway-sync.log`; poll interval ~5s |

---

## 9. Examples

| Example | Command |
|---------|---------|
| Edge scan + DLP | `cargo run --example rust_client_demo` |
| Edge + gateway HTTP | `cargo run --example gateway_http_client` |
| Local stack | `bash scripts/run-local.sh` |

---

## 10. Versions and contracts

- SDK: `virbius-core` 0.1.x
- Edge manifest: `manifest_version: "1"` ([seed-api.md §6](./seed-api.md))
- Architecture: [DESIGN.md](./DESIGN.md)

For integration issues, compare with `cargo run --example rust_client_demo` and curl examples in [seed-api.md](./seed-api.md).
