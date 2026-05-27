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
} virbius_scan_result;

int virbius_init(const char* manifest_url);
int virbius_scan(const virbius_scan_ctx* ctx, const char* text, virbius_scan_result* out);
int virbius_reload(void);

#ifdef __cplusplus
}
#endif
