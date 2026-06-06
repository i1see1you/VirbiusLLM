use serde::Deserialize;
use std::{env, fs, path::PathBuf, sync::{OnceLock, RwLock}};

#[derive(Debug, Clone, Default, Deserialize)]
pub struct DlpRuleBody {
    #[serde(default, rename = "entity_type")]
    pub entity_type: String,
    #[serde(default)]
    pub pattern: Option<String>,
    #[serde(default, rename = "mask_template")]
    pub mask_template: Option<String>,
    #[serde(default)]
    pub priority: Option<i32>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DlpRule {
    pub rule_id: String,
    pub rule_revision: i32,
    pub reason_code: String,
    pub risk_score: i32,
    pub intent_action: String,
    pub enforce_mode: String,
    #[serde(default)]
    pub rollout_state: String,
    pub canary_percent: Option<i32>,
    #[serde(default)]
    pub body: DlpRuleBody,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RuleBody {
    #[serde(default)]
    pub keywords: Vec<String>,
    #[serde(default, rename = "list_type")]
    pub list_type: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct EdgeRule {
    pub rule_id: String,
    pub rule_revision: i32,
    pub reason_code: String,
    pub risk_score: i32,
    pub intent_action: String,
    pub enforce_mode: String,
    #[serde(default)]
    pub rollout_state: String,
    pub canary_percent: Option<i32>,
    #[serde(default)]
    pub body: RuleBody,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct SdkConfig {
    #[serde(default)]
    pub audit_ingest_url: String,
    #[serde(default)]
    pub audit_ingest_token: String,
    #[serde(default = "default_sample_allow")]
    pub audit_sample_rate_allow: f64,
    #[serde(default = "default_sample_hit")]
    pub audit_sample_rate_hit: f64,
    #[serde(default = "default_flush_ms")]
    pub audit_flush_interval_ms: u64,
    #[serde(default = "default_queue_max")]
    pub audit_queue_max: usize,
    #[serde(default = "default_session_key")]
    pub canary_session_key: String,
    #[serde(default = "default_dlp_vault_ttl")]
    pub dlp_vault_ttl_ms: u64,
}

fn default_dlp_vault_ttl() -> u64 {
    1_800_000
}

fn default_sample_allow() -> f64 {
    0.1
}
fn default_sample_hit() -> f64 {
    1.0
}
fn default_flush_ms() -> u64 {
    30000
}
fn default_queue_max() -> usize {
    500
}
fn default_session_key() -> String {
    "device_id".into()
}

#[derive(Debug, Clone, Default, Deserialize)]
struct LegacyLists {
    #[serde(default)]
    deny: LegacySide,
    #[serde(default)]
    allow: LegacySide,
}

#[derive(Debug, Clone, Default, Deserialize)]
struct LegacySide {
    #[serde(default)]
    keywords: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct EdgeManifestFile {
    #[serde(default)]
    tenant_id: String,
    #[serde(default)]
    app_id: String,
    #[serde(default)]
    rules: Vec<EdgeRule>,
    #[serde(default)]
    dlp_rules: Vec<DlpRule>,
    #[serde(default)]
    sdk_config: SdkConfig,
    #[serde(default)]
    lists: LegacyLists,
}

#[derive(Debug, Clone)]
pub struct EdgeManifest {
    pub tenant_id: String,
    pub app_id: String,
    pub rules: Vec<EdgeRule>,
    pub dlp_rules: Vec<DlpRule>,
    pub sdk_config: SdkConfig,
}

static MANIFEST: OnceLock<RwLock<EdgeManifest>> = OnceLock::new();

fn manifest_lock() -> &'static RwLock<EdgeManifest> {
    MANIFEST.get_or_init(|| RwLock::new(read_manifest()))
}

pub fn load() -> EdgeManifest {
    manifest_lock().read().expect("manifest lock").clone()
}

pub fn reload() {
    *manifest_lock().write().expect("manifest lock") = read_manifest();
}

fn read_manifest() -> EdgeManifest {
    let path = manifest_path();
    if let Ok(raw) = fs::read_to_string(&path) {
        if let Ok(parsed) = serde_json::from_str::<EdgeManifestFile>(&raw) {
            let mut rules = parsed.rules;
            let mut dlp_rules = parsed.dlp_rules;
            if !app_id_matches(&parsed.app_id) {
                eprintln!("virbius-core: manifest app_id mismatch; refusing load");
                rules = Vec::new();
                dlp_rules = Vec::new();
            } else if rules.is_empty() {
                rules = legacy_rules(&parsed.lists);
            }
            return EdgeManifest {
                tenant_id: if parsed.tenant_id.is_empty() {
                    env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into())
                } else {
                    parsed.tenant_id
                },
                app_id: resolve_app_id(&parsed.app_id),
                rules,
                dlp_rules,
                sdk_config: parsed.sdk_config,
            };
        }
        if let Ok(legacy) = serde_json::from_str::<LegacyLists>(&raw) {
            return EdgeManifest {
                tenant_id: env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into()),
                app_id: resolve_app_id(""),
                rules: legacy_rules(&legacy),
                dlp_rules: Vec::new(),
                sdk_config: SdkConfig::default(),
            };
        }
    }
    EdgeManifest {
        tenant_id: env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into()),
        app_id: resolve_app_id(""),
        rules: Vec::new(),
        dlp_rules: Vec::new(),
        sdk_config: SdkConfig::default(),
    }
}

fn resolve_app_id(manifest_app_id: &str) -> String {
    if !manifest_app_id.is_empty() {
        return manifest_app_id.to_string();
    }
    env::var("VIRBIUS_APP_ID").unwrap_or_default()
}

fn app_id_matches(manifest_app_id: &str) -> bool {
    let expected = env::var("VIRBIUS_APP_ID").unwrap_or_default();
    if expected.is_empty() || manifest_app_id.is_empty() {
        return true;
    }
    expected == manifest_app_id
}

fn legacy_rules(lists: &LegacyLists) -> Vec<EdgeRule> {
    let mut out = Vec::new();
    if !lists.allow.keywords.is_empty() {
        out.push(EdgeRule {
            rule_id: "edge_l0_content_allow".into(),
            rule_revision: 1,
            reason_code: "EDGE_CONTENT_KEYWORD_ALLOW".into(),
            risk_score: 0,
            intent_action: "allow".into(),
            enforce_mode: "dry_run".into(),
            rollout_state: "dry_run".into(),
            canary_percent: None,
            body: RuleBody {
                keywords: lists.allow.keywords.clone(),
                list_type: "allow".into(),
            },
        });
    }
    if !lists.deny.keywords.is_empty() {
        out.push(EdgeRule {
            rule_id: "edge_l0_content_deny".into(),
            rule_revision: 1,
            reason_code: "EDGE_CONTENT_KEYWORD_DENY".into(),
            risk_score: 100,
            intent_action: "deny".into(),
            enforce_mode: "dry_run".into(),
            rollout_state: "dry_run".into(),
            canary_percent: None,
            body: RuleBody {
                keywords: lists.deny.keywords.clone(),
                list_type: "deny".into(),
            },
        });
    }
    out
}

pub fn manifest_path() -> PathBuf {
    if let Ok(p) = env::var("VIRBIUS_EDGE_MANIFEST_PATH") {
        return PathBuf::from(p);
    }
    let data = env::var("VIRBIUS_DATA_DIR").unwrap_or_else(|_| "./data".into());
    let tenant = env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into());
    if let Ok(app_id) = env::var("VIRBIUS_APP_ID") {
        if !app_id.is_empty() {
            let per_app = PathBuf::from(&data)
                .join("edge")
                .join(&tenant)
                .join(&app_id)
                .join("edge-manifest.json");
            if per_app.exists() {
                return per_app;
            }
        }
    }
    let manifest = PathBuf::from(&data)
        .join("edge")
        .join(format!("{tenant}-edge-manifest.json"));
    if manifest.exists() {
        return manifest;
    }
    env::var("VIRBIUS_EDGE_LISTS_PATH")
        .map(PathBuf::from)
        .unwrap_or_else(|_| {
            PathBuf::from(data)
                .join("edge")
                .join(format!("{tenant}-content-lists.json"))
        })
}

pub fn sdk_config_from_env(base: &SdkConfig) -> SdkConfig {
    let mut cfg = base.clone();
    if let Ok(v) = env::var("VIRBIUS_AUDIT_INGEST_URL") {
        if !v.is_empty() {
            cfg.audit_ingest_url = v;
        }
    }
    if let Ok(v) = env::var("VIRBIUS_AUDIT_INGEST_TOKEN") {
        if !v.is_empty() {
            cfg.audit_ingest_token = v;
        }
    }
    if let Ok(v) = env::var("VIRBIUS_AUDIT_SAMPLE_RATE_ALLOW") {
        if let Ok(n) = v.parse::<f64>() {
            cfg.audit_sample_rate_allow = n;
        }
    }
    cfg
}

pub fn session_key_value<'a>(
    key: &str,
    user_id: Option<&'a str>,
    device_id: Option<&'a str>,
    install_id: Option<&'a str>,
) -> Option<&'a str> {
    match key {
        "install_id" => install_id.filter(|s| !s.is_empty()),
        "user_id" => user_id.filter(|s| !s.is_empty()),
        _ => device_id.filter(|s| !s.is_empty()),
    }
}

#[allow(dead_code)]
pub fn tenant_id() -> String {
    load().tenant_id
}

#[allow(dead_code)]
pub fn app_id() -> String {
    load().app_id
}
