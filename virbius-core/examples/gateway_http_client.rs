//! Example: calling LLM through **APISIX/Kong Gateway** (gateway layer + optional edge layer).
//!
//! Flow: edge `virbius-core` scan (optional) → HTTP POST to gateway OpenAI-compatible endpoint + Virbius headers.
//!
//! Prerequisite: local PoC has APISIX running (default `9080`) with `virbius-guard` mounted, see `docs/POC-SEED-API.md`.
//!
//! ```bash
//! cd virbius-core
//! export VIRBIUS_GATEWAY_URL=http://127.0.0.1:9080/v1/chat/completions
//! # Optional: skip edge scan
//! # export VIRBIUS_SKIP_EDGE=1
//! cargo run --example gateway_http_client
//! ```

use std::path::PathBuf;

use serde_json::json;
use uuid::Uuid;
use virbius_core::{EffectiveAction, EdgeInitConfig, ScanContext, VirbiusEdge};

const DEFAULT_GATEWAY: &str = "http://127.0.0.1:9080/v1/chat/completions";

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let gateway_url = std::env::var("VIRBIUS_GATEWAY_URL").unwrap_or_else(|_| DEFAULT_GATEWAY.into());
    let user_message = std::env::var("VIRBIUS_DEMO_PROMPT")
        .unwrap_or_else(|_| "Hello, explain Rust's ownership in one sentence.".into());

    let ctx = ScanContext {
        user_id: Some("demo-user".into()),
        device_id: Some("demo-device".into()),
        scene: Some("chat".into()),
        trace_id: None,
    };

    println!("=== Virbius gateway HTTP client demo ===\n");
    println!("gateway: {gateway_url}\n");

    let (trace_id, edge_pass) = if skip_edge() {
        let trace_id = Uuid::new_v4().to_string();
        println!("[edge] skipped (VIRBIUS_SKIP_EDGE=1), trace_id={trace_id}");
        (trace_id, false)
    } else {
        edge_scan_and_trace(&ctx, &user_message)?
    };

    match chat_via_gateway(&gateway_url, &ctx, &trace_id, edge_pass, &user_message)? {
        GatewayOutcome::Success { status, body_preview } => {
            println!("\n[gateway] OK HTTP {status}");
            println!("  body preview: {body_preview}");
        }
        GatewayOutcome::Blocked { status, body } => {
            println!("\n[gateway] blocked HTTP {status}");
            println!("  {body}");
        }
        GatewayOutcome::ClientError { status, body } => {
            eprintln!("\n[gateway] client error HTTP {status}");
            eprintln!("  {body}");
            std::process::exit(1);
        }
    }

    Ok(())
}

fn skip_edge() -> bool {
    matches!(
        std::env::var("VIRBIUS_SKIP_EDGE").as_deref(),
        Ok("1") | Ok("true") | Ok("yes")
    )
}

/// Edge L0 scan; if Blocked, do not request the gateway.
fn edge_scan_and_trace(
    ctx: &ScanContext,
    prompt: &str,
) -> Result<(String, bool), Box<dyn std::error::Error>> {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("examples/fixtures/demo-edge-manifest.json");
    let edge = if manifest.exists() {
        let cache_dir = manifest.parent().unwrap().to_path_buf();
        VirbiusEdge::init(EdgeInitConfig {
            offline_manifest_path: Some(manifest),
            cache_dir,
            tenant_id: "default".into(),
            app_id: "demo".into(),
            ..Default::default()
        })?
    } else {
        VirbiusEdge::new()
    };
    let out = edge.scan_with(ctx.clone(), prompt)?;
    println!("[edge] scan action={:?} trace={}", out.action, out.trace_id);

    match out.action {
        EffectiveAction::Block => {
            if let Some(hit) = &out.primary {
                eprintln!(
                    "  blocked locally by {} ({}) — not sending to gateway",
                    hit.rule_id, hit.reason_code
                );
            }
            std::process::exit(0);
        }
        EffectiveAction::Allow | EffectiveAction::Review | EffectiveAction::Captcha => {}
    }

    Ok((out.trace_id, true))
}

enum GatewayOutcome {
    Success { status: u16, body_preview: String },
    Blocked { status: u16, body: String },
    ClientError { status: u16, body: String },
}

/// POST OpenAI Chat Completions format; `virbius-guard` extracts text from `messages[].content` for evaluation.
fn chat_via_gateway(
    url: &str,
    ctx: &ScanContext,
    trace_id: &str,
    edge_pass: bool,
    user_message: &str,
) -> Result<GatewayOutcome, Box<dyn std::error::Error>> {
    let body = json!({
        "model": "gpt-4",
        "messages": [
            { "role": "user", "content": user_message }
        ]
    });

    let mut request = ureq::post(url).set("Content-Type", "application/json");

    // --- Virbius ControlContext → HTTP Headers (see docs/user-guide.en.md §4.2) ---
    // trace_id: same as edge scan, for audit tracing; must be UUID v4.
    request = request.set("X-Virbius-Trace-Id", trace_id);

    if let Some(uid) = ctx.user_id.as_deref().filter(|s| !s.is_empty()) {
        request = request.set("X-Virbius-User-Id", uid);
    }
    if let Some(did) = ctx.device_id.as_deref().filter(|s| !s.is_empty()) {
        request = request.set("X-Virbius-Device-Id", did);
    }
    // When edge scan passed, can hint the gateway (optional, see DESIGN §11.6.9).
    if edge_pass {
        request = request.set("X-Virbius-Edge-Pass", "1");
    }

    // Do NOT set from client (gateway injects per route/service to prevent forgery):
    //   X-Virbius-Tenant, X-Virbius-Scene

    println!("[gateway] POST {url}");
    println!("  X-Virbius-Trace-Id: {trace_id}");
    if edge_pass {
        println!("  X-Virbius-Edge-Pass: 1");
    }

    let response = request.send_json(body)?;

    let status = response.status();
    let text = response.into_string().unwrap_or_default();
    let preview = if text.len() > 240 {
        format!("{}…", &text[..240])
    } else {
        text.clone()
    };

    if status == 403 || status == 428 {
        return Ok(GatewayOutcome::Blocked { status, body: text });
    }
    if status == 400 {
        return Ok(GatewayOutcome::ClientError { status, body: text });
    }
    if !(200..300).contains(&status) {
        return Ok(GatewayOutcome::ClientError { status, body: text });
    }

    Ok(GatewayOutcome::Success {
        status,
        body_preview: preview,
    })
}
