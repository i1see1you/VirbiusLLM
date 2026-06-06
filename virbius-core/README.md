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
use virbius_core::{EffectiveAction, ScanContext, VirbiusEdge};

fn main() -> Result<(), virbius_core::VirbiusError> {
    std::env::set_var(
        "VIRBIUS_EDGE_MANIFEST_PATH",
        "./virbius-core/examples/fixtures/demo-edge-manifest.json",
    );

    let edge = VirbiusEdge::new();
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
- If you set it explicitly, use **UUID v4 or ULID**.

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

## Manifest paths

Per-app layout (recommended after publish):

```bash
export VIRBIUS_TENANT_ID=default
export VIRBIUS_APP_ID=demo-app
export VIRBIUS_DATA_DIR=./data
# loads ./data/edge/{tenant}/{app_id}/edge-manifest.json
```

Or set explicitly:

```bash
export VIRBIUS_EDGE_MANIFEST_PATH=./data/edge/default/demo-app/edge-manifest.json
```

## C ABI

See [include/virbius.h](include/virbius.h): `virbius_init`, `virbius_scan`, `virbius_reload`, `virbius_free_string`.

## License

[MIT License](../LICENSE)
