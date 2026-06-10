# virbius-core

Edge L0 native SDK (Rust + C ABI). Contract: [MVP-OPENSPEC §4](../docs/openspec/MVP-OPENSPEC.md).

License: [MIT](../LICENSE) · 中文：[README.zh.md](README.zh.md)

Integration guide: [docs/user-guide.md](../docs/user-guide.md) (edge SDK, gateway headers, DLP, troubleshooting).

## Rust quick start

```toml
[dependencies]
virbius-core = { path = "../VirbiusLLM/virbius-core" }
```

```rust
use std::path::PathBuf;
use virbius_core::{EffectiveAction, EdgeInitConfig, ScanContext, VirbiusEdge};

fn main() -> Result<(), virbius_core::VirbiusError> {
    let edge = VirbiusEdge::init(EdgeInitConfig {
        offline_manifest_path: Some(
            "./virbius-core/examples/fixtures/demo-edge-manifest.json".into(),
        ),
        cache_dir: PathBuf::from("./cache"),
        ..Default::default()
    })?;
    let outcome = edge.scan_with(
        ScanContext {
            scene: Some("chat".into()),
            ..Default::default()
        },
        "User prompt text",
    )?;

    match outcome.action {
        EffectiveAction::Allow => { /* call LLM; use outcome.trace_id as X-Virbius-Trace-Id */ }
        EffectiveAction::Block => { /* block locally */ }
        EffectiveAction::Review => { /* dry_run hit; may allow and audit */ }
        EffectiveAction::Captcha => { /* captcha flow */ }
    }
    Ok(())
}
```

Run the full example (scan + DLP round-trip with **phone_cn** and **idcard_cn**):

```bash
cd virbius-core
cargo run --example rust_client_demo
```

Gateway example (edge scan + Virbius headers; requires local APISIX PoC):

```bash
export VIRBIUS_GATEWAY_URL=http://127.0.0.1:9080/v1/chat/completions
cargo run --example gateway_http_client
```

- **`trace_id`**: auto-generated UUID v4 if omitted (`trace_id_source=sdk`).
- If you set it explicitly, use **UUID v4** (36 characters).

## DLP (desensitize in / out)

DLP rules live in manifest **`dlp_rules[]`** (separate from keyword **`rules[]`**). `intent_action` is always **`allow`**; DLP does not change scan merge actions.

```rust
let scan = edge.scan_with(ctx.clone(), user_text)?;
let masked = edge.desensitize_in_with(ctx.clone(), user_text)?;
let to_llm = masked.text;
// ... call LLM ...
let restored = edge.desensitize_out_with(&scan.trace_id, &model_reply, ctx);
```

Built-in entity types: `phone_cn`, `idcard_cn`, `email`, `bank_card_cn`, `custom_regex`.  
Place a space before digits when possible — built-in patterns use `\b` word boundaries.

Demo manifest: [examples/fixtures/demo-edge-manifest.json](examples/fixtures/demo-edge-manifest.json).

## Manifest paths (production)

Use [`EdgeInitConfig`](src/sync.rs) + [`VirbiusEdge::init`](src/api/mod.rs) — **not** environment variables in shipped apps:

```rust
use std::path::PathBuf;
use virbius_core::{EdgeInitConfig, VirbiusEdge};

let edge = VirbiusEdge::init(EdgeInitConfig {
    control_base_url: Some("http://127.0.0.1:8080".into()),
    tenant_id: "default".into(),
    app_id: "beta".into(),
    edge_api_key: Some("vrb_tk_...".into()), // when Control VIRBIUS_API_KEY_AUTH_ENABLED
    cache_dir: PathBuf::from("/path/to/app/sandbox/virbius"),
    offline_manifest_path: None,
})?;
```

Offline / local fixture:

```rust
VirbiusEdge::init(EdgeInitConfig {
    offline_manifest_path: Some("./fixtures/demo-edge-manifest.json".into()),
    cache_dir: PathBuf::from("./cache"),
    ..Default::default()
})?;
```

Examples / CI may use `VirbiusEdge::new_from_env()`; see [user-guide §3.2.2](../docs/user-guide.md) for `VIRBIUS_*` variables.

## C ABI

See [include/virbius.h](include/virbius.h): `virbius_init_config_json` (production), `virbius_init` (legacy URL/path), `virbius_scan`, `virbius_reload`, `virbius_free_string`.

## License

[MIT License](../LICENSE)
