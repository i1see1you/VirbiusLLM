mod context;
mod error;
mod result;

pub use context::ScanContext;
pub use error::VirbiusError;
pub use result::{EffectiveAction, RuleHit, ScanOutcome};

pub use crate::trace::TraceIdSource;

use crate::engine::{self, ScanRequest};
use crate::runtime;
use crate::manifest;

/// Edge L0 SDK entry point (idiomatic Rust).
#[derive(Debug, Default, Clone, Copy)]
pub struct VirbiusEdge;

impl VirbiusEdge {
    pub fn new() -> Self {
        let _ = manifest::load();
        runtime::ensure_flush_loop();
        Self
    }

    pub fn reload(&self) {
        manifest::reload();
    }

    pub fn scan(&self, content: &str) -> Result<ScanOutcome, VirbiusError> {
        self.scan_with(ScanContext::default(), content)
    }

    pub fn scan_with(&self, ctx: ScanContext, content: &str) -> Result<ScanOutcome, VirbiusError> {
        if content.is_empty() {
            return Err(VirbiusError::EmptyContent);
        }
        let (trace_id, trace_id_source) = ctx.resolve_trace_id()?;
        let scene = ctx.scene_or_default();
        let engine_result = engine::scan_once(ScanRequest {
            user_id: ctx.user_id.as_deref(),
            device_id: ctx.device_id.as_deref(),
            scene: &scene,
            trace_id: &trace_id,
            trace_id_source,
            content,
        });
        Ok(to_outcome(engine_result))
    }
}

fn to_outcome(engine_result: engine::ScanEngineResult) -> ScanOutcome {
    let action = EffectiveAction::from_effective(&engine_result.merged.effective_action);
    let primary = engine::primary_rule(&engine_result.merged).map(RuleHit::from);
    ScanOutcome {
        action,
        trace_id: engine_result.trace_id,
        trace_id_source: engine_result.trace_id_source,
        max_risk_score: engine_result.merged.max_risk_score,
        primary,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::trace;
    use std::env;
    use std::io::Write;
    use std::sync::{Mutex, OnceLock};
    use tempfile::NamedTempFile;

    static ENV_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

    fn env_lock() -> std::sync::MutexGuard<'static, ()> {
        ENV_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap()
    }

    fn with_manifest(json: &str, f: impl FnOnce()) {
        let _guard = env_lock();
        let mut file = NamedTempFile::new().expect("temp manifest");
        file.write_all(json.as_bytes()).expect("write manifest");
        let path = file.path().to_path_buf();
        unsafe {
            env::set_var("VIRBIUS_EDGE_MANIFEST_PATH", &path);
        }
        manifest::reload();
        f();
        unsafe {
            env::remove_var("VIRBIUS_EDGE_MANIFEST_PATH");
        }
        manifest::reload();
    }

    #[test]
    fn scan_blocks_deny_keyword_in_full_mode() {
        with_manifest(
            r#"{
              "tenant_id": "default",
              "rules": [{
                "rule_id": "edge_kw",
                "rule_revision": 1,
                "reason_code": "EDGE_KW",
                "risk_score": 100,
                "intent_action": "deny",
                "enforce_mode": "full",
                "body": { "list_type": "deny", "keywords": ["jailbreak"] }
              }]
            }"#,
            || {
                let edge = VirbiusEdge::new();
                let out = edge.scan("please jailbreak now").expect("scan");
                assert_eq!(out.action, EffectiveAction::Block);
                assert_eq!(
                    out.primary.as_ref().map(|r| r.rule_id.as_str()),
                    Some("edge_kw")
                );
                assert!(trace::valid_trace_id(&out.trace_id));
                assert_eq!(out.trace_id_source, TraceIdSource::SdkGenerated);
            },
        );
    }

    #[test]
    fn scan_review_on_dry_run_hit() {
        with_manifest(
            r#"{
              "rules": [{
                "rule_id": "edge_kw",
                "rule_revision": 1,
                "reason_code": "EDGE_KW",
                "risk_score": 100,
                "intent_action": "deny",
                "enforce_mode": "dry_run",
                "body": { "list_type": "deny", "keywords": ["jailbreak"] }
              }]
            }"#,
            || {
                let edge = VirbiusEdge::new();
                let out = edge.scan("jailbreak").expect("scan");
                assert_eq!(out.action, EffectiveAction::Review);
            },
        );
    }

    #[test]
    fn scan_rejects_empty_content() {
        let edge = VirbiusEdge::new();
        assert_eq!(edge.scan(""), Err(VirbiusError::EmptyContent));
    }
}
