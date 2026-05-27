mod lists;

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::ptr;

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

#[no_mangle]
pub extern "C" fn virbius_init(_manifest_url: *const c_char) -> c_int {
    lists::init_from_env();
    0
}

#[no_mangle]
pub extern "C" fn virbius_scan(
    _ctx: *const VirbiusScanCtx,
    text: *const c_char,
    out: *mut VirbiusScanResult,
) -> c_int {
    if out.is_null() || text.is_null() {
        return -1;
    }
    let content = unsafe { CStr::from_ptr(text) }.to_string_lossy();
    if let Some(hit) = lists::scan_content(content.as_ref()) {
        unsafe {
            (*out).action = VirbiusAction::Block;
            (*out).rule_id = CString::new(hit.rule_id).unwrap().into_raw();
            (*out).rule_revision = 1;
            (*out).reason_code = CString::new(hit.reason_code).unwrap().into_raw();
            (*out).layer = CString::new("edge").unwrap().into_raw();
        }
        return 0;
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
    0
}
