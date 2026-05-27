use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, time::Duration};

#[derive(Clone)]
pub struct EngineClient {
    base_url: String,
    http: Client,
}

#[derive(Debug, Serialize)]
pub struct EvaluateRequest {
    pub tenant_id: String,
    pub scene: String,
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    pub content: String,
    pub trace_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prior_signals: Option<Vec<Signal>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub vars: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Signal {
    pub rule_id: String,
    pub rule_revision: i32,
    pub source: String,
    pub layer: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub score: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suggest: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason_code: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct EngineDecision {
    pub effective_action: String,
    pub would_block: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enforce_mode: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct EvaluateResponse {
    #[serde(default)]
    pub signals: Vec<Signal>,
    pub decision: EngineDecision,
    pub rule_id: String,
    pub rule_revision: i32,
    pub reason_code: String,
    pub trace_id: String,
    pub degraded: bool,
}

impl EngineClient {
    pub fn new(base_url: impl Into<String>) -> Self {
        let http = Client::builder()
            .timeout(Duration::from_secs(3))
            .build()
            .expect("http client");
        Self {
            base_url: base_url.into().trim_end_matches('/').to_string(),
            http,
        }
    }

    pub async fn evaluate(&self, req: EvaluateRequest) -> Result<EvaluateResponse, reqwest::Error> {
        let url = format!("{}/v1/evaluate", self.base_url);
        let resp = self.http.post(url).json(&req).send().await?;
        let resp = resp.error_for_status()?;
        resp.json().await
    }
}
