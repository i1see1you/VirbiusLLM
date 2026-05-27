mod access_lists;
mod engine;
mod trace;

use axum::{
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use engine::{EngineClient, EvaluateRequest, EvaluateResponse};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    env,
    net::SocketAddr,
    sync::Arc,
};

#[derive(Clone)]
struct AppState {
    engine: EngineClient,
    lists: Arc<access_lists::AccessListChecker>,
}

#[derive(Debug, Deserialize)]
struct AgentEvaluateRequest {
    tenant_id: String,
    scene: String,
    #[serde(default = "default_role")]
    role: String,
    session_id: Option<String>,
    content: String,
    trace_id: String,
    user_id: Option<String>,
    device_id: Option<String>,
    client_ip: Option<String>,
    prior_signals: Option<Vec<engine::Signal>>,
    #[serde(default)]
    query: Option<HashMap<String, String>>,
    #[serde(default)]
    headers: Option<HashMap<String, String>>,
    #[serde(default)]
    vars: Option<HashMap<String, String>>,
}

fn default_role() -> String {
    "user".into()
}

#[derive(Serialize)]
struct ErrorBody {
    code: &'static str,
    message: String,
}

#[derive(Serialize)]
struct HealthBody {
    status: &'static str,
}

#[derive(Serialize)]
struct AgentEvaluateResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    signals: Option<Vec<engine::Signal>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    decision: Option<engine::EngineDecision>,
    #[serde(skip_serializing_if = "Option::is_none")]
    rule_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    rule_revision: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason_code: Option<String>,
    effective_action: String,
    would_block: bool,
    trace_id: String,
    degraded: bool,
}

async fn health() -> Json<HealthBody> {
    Json(HealthBody { status: "ok" })
}

async fn evaluate(
    State(state): State<Arc<AppState>>,
    Json(req): Json<AgentEvaluateRequest>,
) -> Response {
    if req.tenant_id.is_empty() || req.scene.is_empty() || req.content.is_empty() {
        return error_response(
            StatusCode::BAD_REQUEST,
            "INVALID_ARGUMENT",
            "tenant_id, scene, content required",
        );
    }
    if !trace::valid(&req.trace_id) {
        return error_response(
            StatusCode::BAD_REQUEST,
            "INVALID_ARGUMENT",
            "invalid X-Virbius-Trace-Id",
        );
    }

    let query = req.query.clone().unwrap_or_default();
    let headers = req.headers.clone().unwrap_or_default();
    let vars_ctx = state.lists.effective_vars(
        req.vars.as_ref(),
        req.user_id.as_deref(),
        req.device_id.as_deref(),
        req.client_ip.as_deref(),
        &query,
        &headers,
    );

    if let Some(hit) = state.lists.check(
        &req.content,
        req.user_id.as_deref(),
        req.device_id.as_deref(),
        req.client_ip.as_deref(),
        &query,
        &headers,
        req.vars.as_ref(),
    ) {
        return Json(AgentEvaluateResponse {
            signals: None,
            decision: None,
            rule_id: Some(hit.rule_id),
            rule_revision: Some(1),
            reason_code: Some(hit.reason_code),
            effective_action: hit.effective_action,
            would_block: hit.would_block,
            trace_id: req.trace_id,
            degraded: false,
        })
        .into_response();
    }

    let eng_req = EvaluateRequest {
        tenant_id: req.tenant_id,
        scene: req.scene,
        role: if req.role.is_empty() {
            "user".into()
        } else {
            req.role
        },
        session_id: req.session_id,
        content: req.content,
        trace_id: req.trace_id.clone(),
        user_id: req.user_id,
        device_id: req.device_id,
        prior_signals: req.prior_signals,
        vars: Some(vars_ctx),
    };

    match state.engine.evaluate(eng_req).await {
        Ok(resp) => Json(to_agent_response(resp, false)).into_response(),
        Err(_) => Json(AgentEvaluateResponse {
            signals: None,
            decision: None,
            rule_id: None,
            rule_revision: None,
            reason_code: None,
            effective_action: "allow".into(),
            would_block: false,
            trace_id: req.trace_id,
            degraded: true,
        })
        .into_response(),
    }
}

fn to_agent_response(resp: EvaluateResponse, degraded: bool) -> AgentEvaluateResponse {
    AgentEvaluateResponse {
        signals: Some(resp.signals),
        decision: Some(resp.decision.clone()),
        rule_id: Some(resp.rule_id),
        rule_revision: Some(resp.rule_revision),
        reason_code: Some(resp.reason_code),
        effective_action: resp.decision.effective_action,
        would_block: resp.decision.would_block,
        trace_id: resp.trace_id,
        degraded,
    }
}

fn error_response(status: StatusCode, code: &'static str, message: &str) -> Response {
    (
        status,
        Json(ErrorBody {
            code,
            message: message.to_string(),
        }),
    )
        .into_response()
}

#[tokio::main]
async fn main() {
    let listen = env::var("VIRBIUS_AGENT_LISTEN").unwrap_or_else(|_| "127.0.0.1:9070".into());
    let engine_url =
        env::var("VIRBIUS_ENGINE_URL").unwrap_or_else(|_| "http://127.0.0.1:8082".into());

    let state = Arc::new(AppState {
        engine: EngineClient::new(engine_url.clone()),
        lists: Arc::new(access_lists::AccessListChecker::from_env()),
    });

    let app = Router::new()
        .route("/health", get(health))
        .route("/v1/evaluate", post(evaluate))
        .with_state(state);

    let addr: SocketAddr = listen.parse().expect("VIRBIUS_AGENT_LISTEN");
    println!(
        "virbius-gateway-agent listening on {} (engine={})",
        addr, engine_url
    );

    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    axum::serve(listener, app).await.expect("serve");
}
