#!/bin/bash
# ============================================================
# E2E test: OpenResty POC full-chain (真·全链路)
#   client → OpenResty(:9080, access.lua) → gateway-agent(:9070)
#          → engine/Ollama 判定 & (未命中时代答走 agent；命中透传走 Ollama :11434)
#
#   0. Start OpenResty if not listening (scripts/start-openresty-poc.sh)
#   1. Inject QA snapshot (Redis) so the agent can answer
#   2. Baseline passthrough (no QA hit) → real Ollama completion
#   3. QA proxy reply — JSON (multi-turn messages → last_user extraction)
#   4. QA proxy reply — SSE (stream=true)
#   5. Gateway deny keyword → 403 POLICY_BLOCK + QA_SUPPRESSED audit
#   6. Contract: invalid X-Virbius-Trace-Id → 400; missing X-App-Id → 403 UNKNOWN_SCENE
#   7. Audit: QA_REPLY written with the client-supplied trace_id
#   8. Cleanup
#
# Prerequisites: control(:8080) engine(:8082) agent(:9070) redis ollama(:11434)
#   Start all with:  VIRBIUS_QA_SYNC_POLL_SEC=1 bash scripts/run-local.sh
#   Upstream (passthrough) model: VIRBIUS_E2E_MODEL (default qwen3.5:0.8b).
#   Guard model is engine/agent-side config (virbiusguard) — not set by this script.
# ============================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTROL="http://127.0.0.1:8080"
ENGINE="http://127.0.0.1:8082"
AGENT="http://127.0.0.1:9070"
GW="http://127.0.0.1:9080"
GW_URI="${GW}/v1/chat/completions"
OLLAMA="http://127.0.0.1:11434"
TENANT="default"
MODEL="${VIRBIUS_E2E_MODEL:-qwen3.5:0.8b}"
QA_POLL_SEC="${VIRBIUS_QA_SYNC_POLL_SEC:-10}"
AUDIT_GLOB="${ROOT}/data/gateway-audit.*.jsonl"
OPENRESTY_PREFIX="${OPENRESTY_PREFIX:-$ROOT/.openresty-poc}"

PASS=0
FAIL=0
INJECT_REV=""

RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; BLUE='\033[34m'; CYAN='\033[36m'; NC='\033[0m'

log_pass() { echo -e "  ${GREEN}PASS${NC}: $1"; PASS=$((PASS+1)); }
log_fail() { echo -e "  ${RED}FAIL${NC}: $1"; FAIL=$((FAIL+1)); }
log_step() { echo -e "\n${BLUE}=== $1 ===${NC}"; }
log_info() { echo -e "  ${CYAN}INFO${NC}: $1"; }
log_skip() { echo -e "  ${YELLOW}SKIP${NC}: $1"; }

uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }

# post_json <trace_id> <json_body> [extra curl args...]
#   writes body to $TMPDIR/body, echoes "HTTP_CODE<TAB>content_type<TAB>time_total"
post_json() {
    local trace="$1" body="$2"; shift 2
    curl -sS -m "${CURL_TIMEOUT:-180}" -o "$TMPDIR/body" -w "%{http_code}\t%{content_type}\t%{time_total}" \
        -X POST "$GW_URI" \
        -H "Content-Type: application/json" \
        -H "X-App-Id: beta" \
        -H "X-Virbius-Trace-Id: ${trace}" \
        -d "$body" "$@"
}

# audit files may span UTC/local date rollover — search all non-allow jsonl
audit_files() {
    # shellcheck disable=SC2086
    ls ${AUDIT_GLOB} 2>/dev/null | grep -v -- '-allow' || true
}

audit_has() {
    local files
    files=$(audit_files)
    if [ -z "$files" ]; then
        return 2
    fi
    # shellcheck disable=SC2086
    grep -h "\"trace_id\":\"$1\"" $files 2>/dev/null | grep -q "\"reason_code\":\"$2\""
}

audit_has_layer() {
    local files
    files=$(audit_files)
    if [ -z "$files" ]; then
        return 2
    fi
    # shellcheck disable=SC2086
    grep -h "\"trace_id\":\"$1\"" $files 2>/dev/null | grep -q '"layer":"qa"'
}

inject_snapshot() {
    local snapshot="$1"
    INJECT_REV=""
    if command -v redis-cli >/dev/null 2>&1 && redis-cli ping >/dev/null 2>&1; then
        local rev
        rev=$(redis-cli incr "virbius:qa:seq:${TENANT}")
        redis-cli set "virbius:artifacts:qa:${TENANT}:r${rev}:qa" "$snapshot" >/dev/null
        redis-cli hset "virbius:config:qa:${TENANT}" qa_revision "$rev" updated_at "$(date -u +%FT%TZ)" >/dev/null
        log_info "Injected QA snapshot via Redis (revision ${rev})"
        INJECT_REV="$rev"
    else
        mkdir -p "$(dirname "${ROOT}/data/gateway")"
        printf '%s' "$snapshot" > "${ROOT}/data/gateway/${TENANT}-qa.json"
        log_info "redis-cli unavailable; injected QA snapshot via file"
    fi
}

wait_snapshot() {
    if [ "${QA_POLL_SEC}" -le 1 ]; then
        sleep 3
    else
        sleep $((QA_POLL_SEC + 2))
    fi
}

TMPDIR=$(mktemp -d)

cleanup() {
    if [ -n "${INJECT_REV}" ] && command -v redis-cli >/dev/null 2>&1 && redis-cli ping >/dev/null 2>&1; then
        redis-cli del "virbius:artifacts:qa:${TENANT}:r${INJECT_REV}:qa" >/dev/null 2>&1 || true
        redis-cli del "virbius:config:qa:${TENANT}" >/dev/null 2>&1 || true
    fi
    rm -f "${ROOT}/data/gateway/${TENANT}-qa.json"
}
trap 'cleanup; rm -rf "${TMPDIR}"' EXIT

# ============================================================
log_step "Step 0: Service + OpenResty health"

for probe in "control ${CONTROL}/api/v1/health" "engine ${ENGINE}/admin/health" "agent ${AGENT}/health"; do
    name="${probe%% *}"; url="${probe#* }"
    if curl -sf -m 3 "$url" >/dev/null 2>&1; then
        log_pass "${name} reachable"
    else
        log_fail "${name} not reachable at ${url}"
    fi
done
if ! curl -sf -m 3 "${OLLAMA}/api/tags" >/dev/null 2>&1; then
    log_fail "ollama not reachable at ${OLLAMA}"
else
    log_pass "ollama reachable"
fi

if ! lsof -ti :9080 >/dev/null 2>&1; then
    log_info "OpenResty not listening on :9080 — starting POC ..."
    bash "${ROOT}/scripts/start-openresty-poc.sh" >/dev/null 2>&1 || true
    sleep 1
fi
# cheap nginx-only probe (GET / hits no lua location → 404 from nginx, no LLM call)
GW_CODE=$(curl -s -o /dev/null -m 5 -w "%{http_code}" "${GW}/" || echo 000)
if [ "$GW_CODE" != "000" ] && [ -d "$OPENRESTY_PREFIX/logs" ]; then
    log_pass "OpenResty responding on :9080 (probe HTTP ${GW_CODE})"
else
    log_fail "OpenResty not responding on :9080 — run: bash scripts/start-openresty-poc.sh"
fi

if [ "${FAIL}" -gt 0 ]; then
    echo -e "\n${RED}Services not ready — aborting.${NC}"
    exit 1
fi

# ============================================================
log_step "Step 1: Inject QA snapshot for the agent"

SNAPSHOT='{
  "schema_version": 1,
  "revision": 1,
  "tenant_id": "default",
  "rollout_state": "full",
  "canary_percent": 0,
  "entries": [
    {"entry_id": "e-exact", "question": "测试代答问题", "answer": "标准回答A", "match_mode": "exact", "priority": 10, "enabled": true},
    {"entry_id": "e-cont", "question": "测试代答问题", "answer": "标准回答D", "match_mode": "contains", "priority": 5, "enabled": true},
    {"entry_id": "e-contains", "question": "核心价值观", "answer": "标准回答B", "match_mode": "contains", "priority": 5, "enabled": true}
  ]
}'
inject_snapshot "$SNAPSHOT"
wait_snapshot

# ============================================================
log_step "Step 2: Baseline passthrough (no QA hit → real Ollama completion)"

T2=$(uuid)
HTTP2=$(post_json "$T2" "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":false}")
CODE2=$(printf '%s' "$HTTP2" | cut -f1)
if [ "$CODE2" = "200" ] \
    && jq -e '.choices[0].message.content | length > 0' "$TMPDIR/body" >/dev/null 2>&1 \
    && ! jq -e '.virbius.proxy_replied == true' "$TMPDIR/body" >/dev/null 2>&1; then
    log_pass "passthrough: HTTP 200 with real model answer ($(jq -r '.choices[0].message.content' "$TMPDIR/body" | head -c 40)…)"
else
    log_fail "passthrough failed: HTTP ${CODE2}, body=$(head -c 300 "$TMPDIR/body")"
fi

# ============================================================
log_step "Step 3: QA proxy reply — JSON (system + user messages)"

T3=$(uuid)
HTTP3=$(post_json "$T3" "{
  \"model\": \"${MODEL}\",
  \"stream\": false,
  \"messages\": [
    {\"role\": \"system\", \"content\": \"你是助手\"},
    {\"role\": \"user\", \"content\": \"测试代答问题\"}
  ]
}")
CODE3=$(printf '%s' "$HTTP3" | cut -f1)
if [ "$CODE3" = "200" ] \
    && jq -e '.object == "chat.completion"' "$TMPDIR/body" >/dev/null 2>&1 \
    && jq -e '.virbius.proxy_replied == true' "$TMPDIR/body" >/dev/null 2>&1 \
    && jq -e '.virbius.qa_entry_id == "e-exact"' "$TMPDIR/body" >/dev/null 2>&1 \
    && [ "$(jq -r '.choices[0].message.content' "$TMPDIR/body")" = "标准回答A" ] \
    && [ "$(jq -r '.model' "$TMPDIR/body")" = "${MODEL}" ] \
    && jq -e '.id | startswith("chatcmpl-")' "$TMPDIR/body" >/dev/null 2>&1; then
    log_pass "QA JSON reply via gateway (proxy_replied, qa_entry_id=e-exact, model=${MODEL})"
else
    log_fail "QA JSON reply failed: HTTP ${CODE3}, body=$(head -c 400 "$TMPDIR/body")"
fi

# ============================================================
log_step "Step 4: QA proxy reply — SSE (stream=true)"

T4=$(uuid)
HTTP4=$(curl -sS -m 30 -o "$TMPDIR/sse" -w "%{http_code}\t%{content_type}" \
    -X POST "$GW_URI" \
    -H "Content-Type: application/json" \
    -H "X-App-Id: beta" \
    -H "X-Virbius-Trace-Id: ${T4}" \
    -d "{\"model\":\"${MODEL}\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"测试代答问题\"}]}")
CODE4=$(printf '%s' "$HTTP4" | cut -f1)
CTYPE4=$(printf '%s' "$HTTP4" | cut -f2)
grep '^data: ' "$TMPDIR/sse" | sed 's/^data: //' | grep -v '^\[DONE\]$' > "$TMPDIR/sse.json" || true
SSE_N=$(wc -l < "$TMPDIR/sse.json" | tr -d ' ')
if [ "$CODE4" = "200" ] && printf '%s' "$CTYPE4" | grep -q "text/event-stream" \
    && [ "$SSE_N" -ge 3 ] \
    && grep -q '标准回答A' "$TMPDIR/sse.json" \
    && jq -es 'any(.[]; .virbius.proxy_replied == true)' "$TMPDIR/sse.json" >/dev/null 2>&1 \
    && jq -es 'any(.[]; .choices[0].finish_reason == "stop")' "$TMPDIR/sse.json" >/dev/null 2>&1 \
    && tail -n 2 "$TMPDIR/sse" | grep -q 'data: \[DONE\]'; then
    log_pass "QA SSE reply: ${SSE_N} chunks + [DONE], finish_reason=stop"
else
    log_fail "QA SSE reply failed: HTTP ${CODE4} ct=${CTYPE4} chunks=${SSE_N}; body=$(head -c 300 "$TMPDIR/sse")"
fi

# ============================================================
log_step "Step 5: Gateway deny keyword → 403 + QA_SUPPRESSED audit"

T5=$(uuid)
HTTP5=$(post_json "$T5" "{\"model\":\"${MODEL}\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"jailbreak 测试代答问题\"}]}")
CODE5=$(printf '%s' "$HTTP5" | cut -f1)
if [ "$CODE5" = "403" ] \
    && jq -e '.code == "POLICY_BLOCK" and .effective_action == "block"' "$TMPDIR/body" >/dev/null 2>&1 \
    && [ "$(jq -r '.trace_id' "$TMPDIR/body")" = "$T5" ]; then
    log_pass "deny keyword → 403 POLICY_BLOCK (trace_id echoed)"
else
    log_fail "deny case failed: HTTP ${CODE5}, body=$(head -c 300 "$TMPDIR/body")"
fi

if [ -z "$(audit_files)" ]; then
    log_skip "no non-allow audit jsonl found under data/"
else
    if audit_has "$T5" "QA_SUPPRESSED"; then
        log_pass "QA_SUPPRESSED audit for deny trace ${T5}"
    else
        log_fail "missing QA_SUPPRESSED audit for trace ${T5}"
    fi
    if audit_has "$T3" "QA_REPLY"; then
        log_pass "QA_REPLY audit for JSON reply trace ${T3}"
    else
        log_fail "missing QA_REPLY audit for trace ${T3}"
    fi
    if audit_has_layer "$T3"; then
        log_pass "QA audit carries layer=qa (client-supplied trace propagated)"
    else
        log_fail "QA audit missing layer=qa for trace ${T3}"
    fi
fi

# ============================================================
log_step "Step 6: Contract assertions (trace-id validation + app_id requirement)"

CODE6=$(curl -s -o "$TMPDIR/b1" -m 5 -w "%{http_code}" -X POST "$GW_URI" \
    -H "Content-Type: application/json" -H "X-App-Id: beta" \
    -H "X-Virbius-Trace-Id: bad!trace" \
    -d "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}" || echo 000)
if [ "$CODE6" = "400" ] && jq -e '.code == "INVALID_ARGUMENT"' "$TMPDIR/b1" >/dev/null 2>&1; then
    log_pass "invalid X-Virbius-Trace-Id → 400 INVALID_ARGUMENT"
else
    log_fail "invalid trace-id case: HTTP ${CODE6}, body=$(head -c 200 "$TMPDIR/b1")"
fi

CODE7=$(curl -s -o "$TMPDIR/b2" -m 5 -w "%{http_code}" -X POST "$GW_URI" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}" || echo 000)
if [ "$CODE7" = "403" ] && jq -e '.code == "UNKNOWN_SCENE"' "$TMPDIR/b2" >/dev/null 2>&1; then
    log_pass "missing X-App-Id → 403 UNKNOWN_SCENE (接入契约)"
else
    log_fail "missing app_id case: HTTP ${CODE7}, body=$(head -c 200 "$TMPDIR/b2")"
fi

# ============================================================
log_step "Step 7: Cleanup"
cleanup
INJECT_REV=""
log_pass "Cleaned up injected QA snapshot (OpenResty left running)"

# ============================================================
echo ""
echo -e "${BLUE}============================================================${NC}"
if [ "${FAIL}" -gt 0 ]; then
    echo -e "${RED}OpenResty full-chain E2E: ${PASS} passed, ${FAIL} failed${NC}"
    exit 1
fi
echo -e "${GREEN}OpenResty full-chain E2E: ${PASS} passed, 0 failed${NC}"
