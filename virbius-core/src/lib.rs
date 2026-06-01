mod audit;
mod enforce;
mod manifest;
mod matcher;
mod upload;

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::ptr;
use std::sync::OnceLock;
use std::thread;
use std::time::Duration;

#[repr(C)]
pub struct VirbiusScanCtx {
    pub user_id: *const c_char,
    pub device_id: *const c_char,
    pub scene: *const c_char,
    pub trace_id: *const c_char,
}

#[repr(C)]
pub enum VirbiusAction {
    Allow = 0,
    Block = 1,
}

#[repr(C)]
pub struct VirbiusScanResult {
    pub action: VirbiusAction,
    pub rule_id: *const c_char,
    pub rule_revision: c_int,
    pub reason_code: *const c_char,
    pub layer: *const c_char,
}

static FLUSH_STARTED: OnceLock<()> = OnceLock::new();

fn sdk_config() -> manifest::SdkConfig {
    manifest::sdk_config_from_env(&manifest::load().sdk_config)
}

fn ensure_flush_loop() {
    FLUSH_STARTED.get_or_init(|| {
        thread::spawn(|| loop {
            thread::sleep(Duration::from_millis(
                sdk_config().audit_flush_interval_ms.max(5000),
            ));
            audit::flush_pending(&sdk_config());
        });
    });
}

fn cstr_opt(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    Some(unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned())
}

#[no_mangle]
pub extern "C" fn virbius_init(_manifest_url: *const c_char) -> c_int {
    let _ = manifest::load();
    ensure_flush_loop();
    0
}

#[no_mangle]
pub extern "C" fn virbius_scan(
    ctx: *const VirbiusScanCtx,
    text: *const c_char,
    out: *mut VirbiusScanResult,
) -> c_int {
    if out.is_null() || text.is_null() {
        return -1;
    }
    let content = unsafe { CStr::from_ptr(text) }.to_string_lossy();
    let (scene, trace_id, user_id, device_id) = if ctx.is_null() {
        ("default".to_string(), String::new(), None, None)
    } else {
        let c = unsafe { &*ctx };
        (
            cstr_opt(c.scene).unwrap_or_else(|| "default".into()),
            cstr_opt(c.trace_id).unwrap_or_default(),
            cstr_opt(c.user_id),
            cstr_opt(c.device_id),
        )
    };
    let cfg = sdk_config();
    let manifest = manifest::load();
    let session_id = manifest::session_key_value(&cfg.canary_session_key, user_id.as_deref(), device_id.as_deref(), None);
    let hits = matcher::match_rules(content.as_ref(), &manifest.rules);
    let merged = enforce::merge(&hits, session_id);
    audit::maybe_record(
        &cfg,
        if trace_id.is_empty() { "edge-local" } else { &trace_id },
        &scene,
        &merged,
        session_id,
        user_id.as_deref(),
        device_id.as_deref(),
    );
    if enforce::is_enforced_block(&merged.effective_action) {
        if let Some(rule) = merged.primary.as_ref() {
            unsafe {
                (*out).action = VirbiusAction::Block;
                (*out).rule_id = CString::new(rule.rule_id.as_str()).unwrap().into_raw();
                (*out).rule_revision = rule.rule_revision;
                (*out).reason_code = CString::new(rule.reason_code.as_str()).unwrap().into_raw();
                (*out).layer = CString::new("edge").unwrap().into_raw();
            }
            return 0;
        }
    }
    unsafe {
        (*out).action = VirbiusAction::Allow;
        (*out).rule_id = ptr::null();
        (*out).rule_revision = 0;
        (*out).reason_code = ptr::null();
        (*out).layer = ptr::null();
    }
    0
}

#[no_mangle]
pub extern "C" fn virbius_reload() -> c_int {
    manifest::reload();
    0
}
