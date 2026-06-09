mod access_lists;
mod list_redis;
mod audit;
mod bind_scope;
mod cumulative;
mod engine;
mod enforce;
mod logging;
mod policy_engine;
mod script;
mod trace;

use axum::{
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use engine::{EngineClient, EvaluateRequest, EvaluateResponse};
use enforce::GatewayCheckResult;
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
    #[serde(default)]
    route_uri: Option<String>,
    #[serde(default)]
    upstream_id: Option<String>,
    #[serde(default)]
    consumer_id: Option<String>,
    #[serde(default)]
    api_key_group: Option<String>,
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
    effective_action: String,
    max_risk_score: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    rule_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    rule_revision: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason_code: Option<String>,
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

    let mut req = req;
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

    if let Some(gw) = state.lists.check(
        &bind_context(&req, &vars_ctx),
        &req.content,
        req.user_id.as_deref(),
        req.device_id.as_deref(),
        req.client_ip.as_deref(),
        req.session_id.as_deref(),
        &query,
        &headers,
        req.vars.as_ref(),
    ) {
        if enforce::is_terminal(&gw.effective_action) {
            audit::emit_from_gateway(
                &req.trace_id,
                &req.scene,
                &gw,
                req.session_id.as_deref(),
                false,
                req.user_id.as_deref(),
                req.device_id.as_deref(),
            );
            return Json(gateway_response(&req.trace_id, &gw, false)).into_response();
        }
        if gw.effective_action == "review" {
            let mut prior = req.prior_signals.take().unwrap_or_default();
            prior.extend(gw.prior_signals);
            return forward_engine(state, req, vars_ctx, Some(prior)).await;
        }
    }

    let prior_signals = req.prior_signals.take();
    forward_engine(state, req, vars_ctx, prior_signals).await
}

async fn forward_engine(
    state: Arc<AppState>,
    req: AgentEvaluateRequest,
    vars_ctx: HashMap<String, String>,
    prior_signals: Option<Vec<engine::Signal>>,
) -> Response {
    let trace_id = req.trace_id.clone();
    let scene = req.scene.clone();
    let session_id = req.session_id.clone();
    let user_id = req.user_id.clone();
    let device_id = req.device_id.clone();

    let eng_req = EvaluateRequest {
        tenant_id: req.tenant_id,
        scene: req.scene.clone(),
        role: if req.role.is_empty() {
            "user".into()
        } else {
            req.role.clone()
        },
        session_id: req.session_id,
        content: req.content,
        trace_id: trace_id.clone(),
        user_id: req.user_id,
        device_id: req.device_id,
        prior_signals,
        vars: Some(vars_ctx),
        route_uri: req.route_uri.clone(),
        upstream_id: req.upstream_id.clone(),
        consumer_id: req.consumer_id.clone(),
        api_key_group: req.api_key_group.clone(),
    };

    match state.engine.evaluate(eng_req).await {
        Ok(resp) => {
            let agent = to_agent_response(resp, false);
            audit::emit(build_engine_audit(
                &trace_id,
                &scene,
                &session_id,
                &user_id,
                &device_id,
                &agent,
            ));
            Json(agent).into_response()
        }
        Err(_) => {
            audit::emit_allow(
                &trace_id,
                &scene,
                session_id.as_deref(),
                true,
                user_id.as_deref(),
                device_id.as_deref(),
            );
            Json(AgentEvaluateResponse {
                effective_action: "allow".into(),
                max_risk_score: 0,
                rule_id: None,
                rule_revision: None,
                reason_code: None,
                trace_id,
                degraded: true,
            })
            .into_response()
        }
    }
}

fn build_engine_audit(
    trace_id: &str,
    scene: &str,
    session_id: &Option<String>,
    user_id: &Option<String>,
    device_id: &Option<String>,
    agent: &AgentEvaluateResponse,
) -> audit::AuditEvent {
    audit::build_event(
        trace_id,
        scene,
        agent.rule_id.as_deref(),
        agent.rule_revision,
        agent.reason_code.as_deref(),
        &agent.effective_action,
        agent.max_risk_score,
        None,
        None,
        session_id.as_deref(),
        agent.degraded,
        user_id.as_deref(),
        device_id.as_deref(),
    )
}

fn gateway_response(trace_id: &str, gw: &GatewayCheckResult, degraded: bool) -> AgentEvaluateResponse {
    let primary = gw.primary.as_ref();
    AgentEvaluateResponse {
        effective_action: gw.effective_action.clone(),
        max_risk_score: gw.max_risk_score,
        rule_id: primary.map(|p| p.rule_id.clone()),
        rule_revision: primary.map(|p| p.rule_revision),
        reason_code: primary.map(|p| p.reason_code.clone()),
        trace_id: trace_id.to_string(),
        degraded,
    }
}

fn to_agent_response(resp: EvaluateResponse, degraded: bool) -> AgentEvaluateResponse {
    AgentEvaluateResponse {
        effective_action: resp.effective_action,
        max_risk_score: resp.max_risk_score,
        rule_id: Some(resp.rule_id),
        rule_revision: Some(resp.rule_revision),
        reason_code: Some(resp.reason_code),
        trace_id: resp.trace_id,
        degraded,
    }
}

fn bind_context(req: &AgentEvaluateRequest, vars: &HashMap<String, String>) -> bind_scope::BindContext {
    bind_scope::BindContext {
        scene: req.scene.clone(),
        route_uri: req.route_uri.clone(),
        app_id: vars.get("app_id").cloned(),
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
    if let Err(e) = logging::init() {
        eprintln!("virbius-gateway-agent: logging init failed: {e}");
    }

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
    log::info!(
        "virbius-gateway-agent listening on {} (engine={})",
        addr,
        engine_url
    );

    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    axum::serve(listener, app).await.expect("serve");
}
