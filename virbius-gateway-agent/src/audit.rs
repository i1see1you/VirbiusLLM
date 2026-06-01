use chrono::Utc;
use serde::Serialize;
use std::{
    env,
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::{OnceLock, RwLock},
};

#[derive(Debug, Clone, Serialize)]
pub struct AuditEvent {
    pub trace_id: String,
    pub trace_id_source: String,
    pub tenant_id: String,
    pub scene: String,
    pub layer: String,
    pub rule_id: String,
    pub rule_revision: i32,
    pub reason_code: String,
    pub effective_action: String,
    pub max_risk_score: i32,
    pub rollout_state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub canary_percent: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub in_canary_bucket: Option<bool>,
    pub degraded: bool,
    pub intercepted_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
}

struct AuditConfig {
    redis_url: Option<String>,
    stream_key: String,
    jsonl_path: PathBuf,
    tenant_id: String,
}

static CONFIG: OnceLock<RwLock<AuditConfig>> = OnceLock::new();

fn config() -> &'static RwLock<AuditConfig> {
    CONFIG.get_or_init(|| {
        let data_dir = env::var("VIRBIUS_DATA_DIR").unwrap_or_else(|_| "./data".into());
        let jsonl = env::var("VIRBIUS_GATEWAY_AUDIT_PATH").map(PathBuf::from).unwrap_or_else(|_| {
            PathBuf::from(data_dir).join("gateway-audit.jsonl")
        });
        RwLock::new(AuditConfig {
            redis_url: env::var("VIRBIUS_REDIS_URL").ok().filter(|s| !s.is_empty()),
            stream_key: env::var("VIRBIUS_AUDIT_STREAM_KEY")
                .unwrap_or_else(|_| "virbius:audit:events".into()),
            jsonl_path: jsonl,
            tenant_id: env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into()),
        })
    })
}

pub fn tenant_id() -> String {
    config().read().map(|c| c.tenant_id.clone()).unwrap_or_else(|_| "default".into())
}

pub fn emit(event: AuditEvent) {
    let cfg = match config().read() {
        Ok(c) => c,
        Err(_) => return,
    };
    let payload = match serde_json::to_string(&event) {
        Ok(p) => p,
        Err(_) => return,
    };
    if let Some(parent) = cfg.jsonl_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&cfg.jsonl_path) {
        let _ = writeln!(f, "{}", payload);
    }
    if let Some(url) = cfg.redis_url.as_deref() {
        if let Ok(client) = redis::Client::open(url) {
            if let Ok(mut conn) = client.get_connection() {
                let _: Result<String, _> = redis::cmd("XADD")
                    .arg(&cfg.stream_key)
                    .arg("*")
                    .arg("payload")
                    .arg(&payload)
                    .query(&mut conn);
            }
        }
    }
}

pub fn build_event(
    trace_id: &str,
    scene: &str,
    rule_id: Option<&str>,
    rule_revision: Option<i32>,
    reason_code: Option<&str>,
    effective_action: &str,
    max_risk_score: i32,
    enforce_mode: Option<&str>,
    canary_percent: Option<i32>,
    session_id: Option<&str>,
    degraded: bool,
    user_id: Option<&str>,
    device_id: Option<&str>,
) -> AuditEvent {
    let rollout = enforce_mode
        .map(|m| m.trim().to_ascii_lowercase())
        .filter(|m| !m.is_empty())
        .unwrap_or_else(|| "dry_run".into());
    let in_bucket = if rollout == "canary" {
        canary_percent.map(|pct| crate::enforce::in_canary_bucket(session_id, pct))
    } else {
        None
    };
    AuditEvent {
        trace_id: trace_id.to_string(),
        trace_id_source: "client".into(),
        tenant_id: tenant_id(),
        scene: scene.to_string(),
        layer: "gateway".into(),
        rule_id: rule_id.unwrap_or("").to_string(),
        rule_revision: rule_revision.unwrap_or(0),
        reason_code: reason_code.unwrap_or("").to_string(),
        effective_action: effective_action.to_string(),
        max_risk_score,
        rollout_state: rollout,
        canary_percent,
        in_canary_bucket: in_bucket,
        degraded,
        intercepted_at: Utc::now().to_rfc3339(),
        user_id: user_id.map(|s| s.to_string()),
        device_id: device_id.map(|s| s.to_string()),
    }
}

pub fn emit_from_gateway(
    trace_id: &str,
    scene: &str,
    gw: &crate::enforce::GatewayCheckResult,
    session_id: Option<&str>,
    degraded: bool,
    user_id: Option<&str>,
    device_id: Option<&str>,
) {
    let primary = gw.primary.as_ref();
    let event = build_event(
        trace_id,
        scene,
        primary.map(|p| p.rule_id.as_str()),
        primary.map(|p| p.rule_revision),
        primary.map(|p| p.reason_code.as_str()),
        &gw.effective_action,
        gw.max_risk_score,
        primary.map(|p| p.enforce_mode.as_str()),
        primary.and_then(|p| p.canary_percent),
        session_id,
        degraded,
        user_id,
        device_id,
    );
    emit(event);
}

pub fn emit_allow(
    trace_id: &str,
    scene: &str,
    session_id: Option<&str>,
    degraded: bool,
    user_id: Option<&str>,
    device_id: Option<&str>,
) {
    emit(build_event(
        trace_id,
        scene,
        None,
        None,
        None,
        "allow",
        0,
        Some("dry_run"),
        None,
        session_id,
        degraded,
        user_id,
        device_id,
    ));
}
