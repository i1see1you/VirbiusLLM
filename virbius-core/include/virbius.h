#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
  VIRBIUS_ALLOW = 0,
  VIRBIUS_BLOCK = 1
} virbius_action;

typedef struct {
  const char* user_id;
  const char* device_id;
  const char* scene;
  const char* trace_id;
} virbius_scan_ctx;

typedef struct {
  virbius_action action;
  const char* rule_id;
  int rule_revision;
  const char* reason_code;
  const char* layer;
  const char* trace_id;       /* SDK 生成或透传；须 virbius_free_string 释放 */
} virbius_scan_result;

/**
 * Legacy init: pass Control base URL (http(s)://...) or offline manifest file path.
 * Production mobile apps should use virbius_init_config_json instead.
 */
int virbius_init(const char* manifest_url);

/**
 * Production init — JSON object matching EdgeInitConfig:
 * {
 *   "control_base_url": "https://control.example.com",  // optional if offline_manifest_path set
 *   "tenant_id": "default",
 *   "app_id": "beta",
 *   "cache_dir": "/var/mobile/.../virbius",
 *   "edge_api_key": "vrb_edge_...",          // optional; required when Control auth enabled
 *   "offline_manifest_path": null
 * }
 */
int virbius_init_config_json(const char* json);

int virbius_scan(const virbius_scan_ctx* ctx, const char* text, virbius_scan_result* out);
int virbius_reload(void);
void virbius_free_string(char* p);

#ifdef __cplusplus
}
#endif
