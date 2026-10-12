#!/bin/bash
# ============================================================
# E2E test: QA proxy reply (安全知识代答) — M1a
#   1. Health check (control + engine + agent)
#   2. Control CRUD + validator rejection
#   3. Agent reply consume (exact / contains / regex / miss) via Redis-injected snapshot,
#      plus normalization (full-width + zero-width), priority arbitration, disabled entry
#   3.5 Rollout states: canary 0% / canary 100% / dry_run consume gating
#   4. Engine-deny suppression (QA candidate must not override a block)
#      + audit assertions (QA_REPLY / QA_SUPPRESSED by trace_id)
#   5. Optional: full control publish chain when Ollama is reachable + Redis ack key
#   6. Cleanup
#
# Covered consume gates: exact/contains/regex/miss, normalization (NFKC + zero-width),
#   priority arbitration, enabled=false, full/canary0/canary100/dry_run rollout,
#   deny suppression, audit events (QA_REPLY/QA_SUPPRESSED/layer=qa), publish + Redis ack.
# Known gaps (unit-tested in virbius-gateway-agent/src/qa.rs, not in shell):
#   captcha / review / degraded suppression legs — need engine-side fixtures or a
#   stopped engine, not deterministic in this script.
#   Gateway-side Lua path (lib/prompt.lua last-user extraction + lib/reply.lua JSON/SSE
#   synthesis) — requires APISIX/OpenResty, not covered by any shell test yet.
#
# Prerequisites:
#   - Control API on http://127.0.0.1:8080
#   - Engine API on http://127.0.0.1:8082
#   - Gateway-agent on http://127.0.0.1:9070 (started from repo root so ./data resolves)
#   - Redis (for the optional publish chain + node/ack keys)
#   - Ollama with virbiusguard on http://127.0.0.1:11434 (optional, enables step 5)
#
# Tip: start services with a fast QA poll to keep this test short:
#   VIRBIUS_QA_SYNC_POLL_SEC=1 bash scripts/run-local.sh
# ============================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTROL="http://127.0.0.1:8080"
ENGINE="http://127.0.0.1:8082"
AGENT="http://127.0.0.1:9070"
OLLAMA="http://127.0.0.1:11434"
TENANT="default"
QA_FILE="${ROOT}/data/gateway/${TENANT}-qa.json"
QA_POLL_SEC="${VIRBIUS_QA_SYNC_POLL_SEC:-10}"
AUDIT_LOG="${ROOT}/data/gateway-audit.$(date +%F).jsonl"

PASS=0
FAIL=0

RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; BLUE='\033[34m'; CYAN='\033[36m'; NC='\033[0m'

log_pass() { echo -e "  ${GREEN}PASS${NC}: $1"; PASS=$((PASS+1)); }
log_fail() { echo -e "  ${RED}FAIL${NC}: $1"; FAIL=$((FAIL+1)); }
log_step() { echo -e "\n${BLUE}=== $1 ===${NC}"; }
log_info() { echo -e "  ${CYAN}INFO${NC}: $1"; }
log_skip() { echo -e "  ${YELLOW}SKIP${NC}: $1"; }

api_get() { curl -s "${CONTROL}$1"; }
api_put() { curl -s -X PUT "${CONTROL}$1" -H "Content-Type: application/json" -d "$2"; }
api_post() { curl -s -X POST "${CONTROL}$1" -H "Content-Type: application/json" -d "${2:-{}}"; }
api_delete() { curl -s -X DELETE "${CONTROL}$1"; }
http_code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

agent_eval() {
    local content="$1"
    local last_user="${2:-}"
    local trace_id
    trace_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"
    local payload
    payload=$(python3 - "$content" "$last_user" "$trace_id" "$TENANT" <<'PY'
import json, sys
content, last_user, trace_id, tenant = sys.argv[1:5]
body = {"tenant_id": tenant, "scene": "chat", "role": "user", "content": content, "trace_id": trace_id}
if last_user:
    body["last_user_message"] = last_user
print(json.dumps(body))
PY
)
    curl -s -X POST "${AGENT}/v1/evaluate" -H "Content-Type: application/json" -d "${payload}"
}

# inject_snapshot <json> — write QA snapshot to Redis pointer (+blob) or file fallback.
# Result revision is exported in INJECT_REV.
inject_snapshot() {
    local snapshot="$1"
    INJECT_REV=""
    if command -v redis-cli >/dev/null 2>&1 && redis-cli ping >/dev/null 2>&1; then
        local rev
        rev=$(redis-cli incr "virbius:qa:seq:${TENANT}")
        redis-cli set "virbius:artifacts:qa:${TENANT}:r${rev}:qa" "$snapshot" >/dev/null
        redis-cli hset "virbius:config:qa:${TENANT}" qa_revision "$rev" updated_at "$(date -u +%FT%TZ)" >/dev/null
        log_info "Injected snapshot via Redis (revision ${rev})"
        INJECT_REV="$rev"
    else
        mkdir -p "$(dirname "${QA_FILE}")"
        printf '%s' "$snapshot" > "${QA_FILE}"
        log_info "Redis unavailable; injected snapshot via file ${QA_FILE}"
    fi
}

# audit_has <trace_id> <reason_code> — grep the dated non-allow audit jsonl (QA events are never sampled)
audit_has() {
    if [ ! -f "${AUDIT_LOG}" ]; then
        return 2
    fi
    grep "\"trace_id\":\"$1\"" "${AUDIT_LOG}" 2>/dev/null | grep -q "\"reason_code\":\"$2\""
}

wait_snapshot() {
    if [ "${QA_POLL_SEC}" -le 1 ]; then
        sleep 3
    else
        sleep $((QA_POLL_SEC + 2))
    fi
}

TMPDIR=$(mktemp -d)

QA_ID=""

cleanup() {
    rm -f "${QA_FILE}"
    if [ -n "${QA_ID}" ]; then
        api_delete "/api/v1/admin/tenants/${TENANT}/qa/${QA_ID}" >/dev/null 2>&1 || true
    fi
    if command -v redis-cli >/dev/null 2>&1; then
        redis-cli del "virbius:config:qa:${TENANT}" >/dev/null 2>&1 || true
    fi
}
trap 'cleanup; rm -rf "${TMPDIR}"' EXIT

# ============================================================
log_step "Step 1: Health check"
HC=$(api_get "/api/v1/health")
if echo "$HC" | jq -e '.status == "ok" or .code == 0' >/dev/null 2>&1; then
    log_pass "Control API is healthy"
else
    log_fail "Control API health check failed: $HC"
fi
EH=$(curl -sf "${ENGINE}/admin/health" 2>/dev/null || echo '{"status":"down"}')
if echo "$EH" | jq -e '.status == "ok" or .code == 0' >/dev/null 2>&1; then
    log_pass "Engine API is healthy"
else
    log_fail "Engine API health check failed"
fi
AH=$(curl -sf "${AGENT}/health" 2>/dev/null || echo '{"status":"down"}')
if echo "$AH" | jq -e '.status == "ok"' >/dev/null 2>&1; then
    log_pass "Gateway-agent is healthy"
else
    log_fail "Gateway-agent health check failed"
fi

if [ "${FAIL}" -gt 0 ]; then
    echo -e "\n${RED}Services not ready — aborting.${NC}"
    echo "Start them with: bash scripts/run-local.sh"
    exit 1
fi

# ============================================================
log_step "Step 2: Control CRUD + validator"

CREATE=$(api_post "/api/v1/admin/tenants/${TENANT}/qa" '{"name":"e2e-qa","rollout_state":"full"}')
QA_ID=$(echo "$CREATE" | jq -r '.data.qa_id // empty')
if [ -n "$QA_ID" ]; then
    log_pass "Created qa library ${QA_ID}"
else
    log_fail "Create qa library failed: $CREATE"
fi

ENTRIES='{"entries":[
  {"entry_id":"e-exact","question":"测试代答问题","answer":"标准回答A","match_mode":"exact","priority":10},
  {"entry_id":"e-contains","question":"核心价值观","answer":"标准回答B","match_mode":"contains","priority":5},
  {"entry_id":"e-regex","question":"台.*湾","answer":"标准回答C","match_mode":"regex","priority":5}
]}'
PUT=$(api_put "/api/v1/admin/tenants/${TENANT}/qa/${QA_ID}/entries" "$ENTRIES")
COUNT=$(echo "$PUT" | jq -r '.data | length' 2>/dev/null || echo 0)
if [ "$COUNT" = "3" ]; then
    log_pass "Replaced 3 entries"
else
    log_fail "Replace entries failed: $PUT"
fi

REJECT_CODE=$(http_code -X PUT "${CONTROL}/api/v1/admin/tenants/${TENANT}/qa/${QA_ID}/entries" \
    -H "Content-Type: application/json" \
    -d '{"entries":[{"question":"q","answer":"a","match_mode":"similar"}]}')
if [ "$REJECT_CODE" = "400" ]; then
    log_pass "Unsupported match_mode rejected (HTTP 400)"
else
    log_fail "Expected HTTP 400 for match_mode=similar, got ${REJECT_CODE}"
fi

LOOK_CODE=$(http_code -X PUT "${CONTROL}/api/v1/admin/tenants/${TENANT}/qa/${QA_ID}/entries" \
    -H "Content-Type: application/json" \
    -d '{"entries":[{"question":"(?=abc)","answer":"a","match_mode":"regex"}]}')
if [ "$LOOK_CODE" = "400" ]; then
    log_pass "Lookaround regex rejected (HTTP 400)"
else
    log_fail "Expected HTTP 400 for lookaround regex, got ${LOOK_CODE}"
fi

# ============================================================
log_step "Step 3: Agent reply consume (Redis-injected snapshot)"

SNAPSHOT='{
  "schema_version": 1,
  "revision": 1,
  "tenant_id": "default",
  "rollout_state": "full",
  "canary_percent": 0,
  "entries": [
    {"entry_id": "e-exact", "question": "测试代答问题", "answer": "标准回答A", "match_mode": "exact", "priority": 10, "enabled": true},
    {"entry_id": "e-contains", "question": "核心价值观", "answer": "标准回答B", "match_mode": "contains", "priority": 5, "enabled": true},
    {"entry_id": "e-regex", "question": "台.*湾", "answer": "标准回答C", "match_mode": "regex", "priority": 5, "enabled": true},
    {"entry_id": "e-norm", "question": "Hello世界", "answer": "归一化回答", "match_mode": "exact", "priority": 10, "enabled": true},
    {"entry_id": "e-para-a", "question": "仲裁关键词", "answer": "仲裁答案A", "match_mode": "contains", "priority": 1, "enabled": true},
    {"entry_id": "e-para-b", "question": "仲裁关键词", "answer": "仲裁答案B", "match_mode": "contains", "priority": 50, "enabled": true},
    {"entry_id": "e-off", "question": "停用问题", "answer": "停用答案", "match_mode": "exact", "priority": 10, "enabled": false}
  ]
}'

inject_snapshot "$SNAPSHOT"
wait_snapshot

R1=$(agent_eval "测试代答问题" "测试代答问题")
TID_REPLY=$(echo "$R1" | jq -r '.trace_id // empty')
if [ "$(echo "$R1" | jq -r '.effective_action')" = "reply" ] \
    && [ "$(echo "$R1" | jq -r '.reply_text')" = "标准回答A" ] \
    && [ "$(echo "$R1" | jq -r '.qa_entry_id')" = "e-exact" ]; then
    log_pass "exact hit → reply (entry=e-exact)"
else
    log_fail "exact hit failed: $R1"
fi

R2=$(agent_eval "我想了解核心价值观" "我想了解核心价值观")
if [ "$(echo "$R2" | jq -r '.effective_action')" = "reply" ] && [ "$(echo "$R2" | jq -r '.reply_text')" = "标准回答B" ]; then
    log_pass "contains hit → reply"
else
    log_fail "contains hit failed: $R2"
fi

R3=$(agent_eval "台湾是不是一个国家" "台湾是不是一个国家")
if [ "$(echo "$R3" | jq -r '.effective_action')" = "reply" ] && [ "$(echo "$R3" | jq -r '.reply_text')" = "标准回答C" ]; then
    log_pass "regex hit → reply"
else
    log_fail "regex hit failed: $R3"
fi

R4=$(agent_eval "今天天气不错" "今天天气不错")
if [ "$(echo "$R4" | jq -r '.effective_action')" != "reply" ]; then
    log_pass "miss → not replied (${R4})"
else
    log_fail "unexpected reply on miss: $R4"
fi

# --- normalization: full-width ASCII + zero-width char (U+200B) must still match "Hello世界"
ZWSP=$(printf '\342\200\213')
R7=$(agent_eval "ＨＥＬＬＯ${ZWSP}世界" "ＨＥＬＬＯ${ZWSP}世界")
if [ "$(echo "$R7" | jq -r '.effective_action')" = "reply" ] \
    && [ "$(echo "$R7" | jq -r '.reply_text')" = "归一化回答" ] \
    && [ "$(echo "$R7" | jq -r '.qa_entry_id')" = "e-norm" ]; then
    log_pass "normalization (full-width + zero-width) → reply"
else
    log_fail "normalization match failed: $R7"
fi

# --- arbitration: same mode, higher priority wins (e-para-b p50 > e-para-a p1)
R8=$(agent_eval "这是仲裁关键词测试" "这是仲裁关键词测试")
if [ "$(echo "$R8" | jq -r '.effective_action')" = "reply" ] \
    && [ "$(echo "$R8" | jq -r '.qa_entry_id')" = "e-para-b" ] \
    && [ "$(echo "$R8" | jq -r '.reply_text')" = "仲裁答案B" ]; then
    log_pass "priority arbitration → e-para-b"
else
    log_fail "priority arbitration failed: $R8"
fi

# --- disabled entry must never be consumed
R9=$(agent_eval "停用问题" "停用问题")
if [ "$(echo "$R9" | jq -r '.effective_action')" != "reply" ]; then
    log_pass "disabled entry → not replied"
else
    log_fail "disabled entry unexpectedly consumed: $R9"
fi

# ============================================================
log_step "Step 3.5: Rollout state gating (canary / dry_run)"

SN_CANARY0=$(printf '%s' "$SNAPSHOT" | sed 's/"rollout_state": "full"/"rollout_state": "canary"/')
SN_CANARY100=$(printf '%s' "$SN_CANARY0" | sed 's/"canary_percent": 0/"canary_percent": 100/')
SN_DRYRUN=$(printf '%s' "$SNAPSHOT" | sed 's/"rollout_state": "full"/"rollout_state": "dry_run"/')

inject_snapshot "$SN_CANARY0"
wait_snapshot
RC0=$(agent_eval "测试代答问题" "测试代答问题")
if [ "$(echo "$RC0" | jq -r '.effective_action')" != "reply" ]; then
    log_pass "canary 0% → not consumed"
else
    log_fail "canary 0% unexpectedly replied: $RC0"
fi

inject_snapshot "$SN_CANARY100"
wait_snapshot
RC100=$(agent_eval "测试代答问题" "测试代答问题")
if [ "$(echo "$RC100" | jq -r '.effective_action')" = "reply" ] \
    && [ "$(echo "$RC100" | jq -r '.reply_text')" = "标准回答A" ]; then
    log_pass "canary 100% → consumed"
else
    log_fail "canary 100% did not reply: $RC100"
fi

inject_snapshot "$SN_DRYRUN"
wait_snapshot
RDR=$(agent_eval "测试代答问题" "测试代答问题")
if [ "$(echo "$RDR" | jq -r '.effective_action')" != "reply" ]; then
    log_pass "dry_run → candidate only, not consumed"
else
    log_fail "dry_run unexpectedly replied: $RDR"
fi

# restore full-state snapshot for the suppression step
inject_snapshot "$SNAPSHOT"
wait_snapshot

# ============================================================
log_step "Step 4: Engine-deny style suppression (gateway deny keyword)"

R5=$(agent_eval "jailbreak 测试代答问题" "测试代答问题")
TID_SUPPRESS=$(echo "$R5" | jq -r '.trace_id // empty')
if [ "$(echo "$R5" | jq -r '.effective_action')" = "block" ]; then
    log_pass "QA candidate suppressed by gateway deny (block)"
else
    log_fail "expected block (deny before reply), got: $R5"
fi

# ============================================================
log_step "Step 4b: Audit assertions (QA_REPLY / QA_SUPPRESSED)"

if [ ! -f "${AUDIT_LOG}" ]; then
    log_skip "audit jsonl not found: ${AUDIT_LOG}"
else
    if [ -n "${TID_REPLY}" ] && audit_has "${TID_REPLY}" "QA_REPLY"; then
        log_pass "QA_REPLY audit event written (trace=${TID_REPLY})"
    else
        log_fail "missing QA_REPLY audit event for trace=${TID_REPLY}"
    fi

    if [ -n "${TID_SUPPRESS}" ] && audit_has "${TID_SUPPRESS}" "QA_SUPPRESSED"; then
        log_pass "QA_SUPPRESSED audit event written (trace=${TID_SUPPRESS})"
    else
        log_fail "missing QA_SUPPRESSED audit event for trace=${TID_SUPPRESS}"
    fi

    if grep "\"trace_id\":\"${TID_REPLY}\"" "${AUDIT_LOG}" 2>/dev/null | grep -q '"layer":"qa"'; then
        log_pass "QA audit event carries layer=qa"
    else
        log_fail "QA audit event missing layer=qa"
    fi
fi

# ============================================================
log_step "Step 5: Full publish chain (optional, requires Ollama)"

if curl -sf "${OLLAMA}/api/tags" >/dev/null 2>&1; then
    JOB=$(api_post "/api/v1/admin/tenants/${TENANT}/qa/${QA_ID}/publish")
    JOB_ID=$(echo "$JOB" | jq -r '.data.publish_id // empty')
    if [ -n "$JOB_ID" ]; then
        log_pass "Submitted publish job ${JOB_ID}"
        STATUS=""
        for _ in $(seq 1 30); do
            sleep 2
            STATUS=$(api_get "/api/v1/admin/tenants/${TENANT}/qa/${QA_ID}/jobs" | jq -r ".data[] | select(.publish_id==\"${JOB_ID}\") | .status")
            [ "$STATUS" = "acked" ] && break
            [ "$STATUS" = "failed" ] && break
        done
        if [ "$STATUS" = "acked" ]; then
            log_pass "Publish job acked"
            wait_snapshot
            R6=$(agent_eval "测试代答问题" "测试代答问题")
            if [ "$(echo "$R6" | jq -r '.effective_action')" = "reply" ]; then
                log_pass "Redis-published snapshot consumed by agent"
            else
                log_fail "agent did not reply from published snapshot: $R6"
            fi

            # Redis ack key: highest acked revision must equal the consumed pointer revision
            if command -v redis-cli >/dev/null 2>&1 && redis-cli ping >/dev/null 2>&1; then
                ACK_MAX=0
                ACK_STATUS=""
                for k in $(redis-cli keys "virbius:ack:qa:${TENANT}:*" 2>/dev/null); do
                    r=$(redis-cli hget "$k" revision 2>/dev/null || echo 0)
                    s=$(redis-cli hget "$k" status 2>/dev/null || echo "")
                    if [ -n "$r" ] && [ "$r" -gt "$ACK_MAX" ] 2>/dev/null; then
                        ACK_MAX="$r"
                        ACK_STATUS="$s"
                    fi
                done
                POINTER_REV=$(redis-cli hget "virbius:config:qa:${TENANT}" qa_revision 2>/dev/null || echo "")
                if [ "$ACK_MAX" -gt 0 ] 2>/dev/null && [ "$ACK_MAX" = "$POINTER_REV" ] && [ "$ACK_STATUS" = "ok" ]; then
                    log_pass "Redis ack key matches pointer revision (${ACK_MAX}, status=ok)"
                else
                    log_fail "ack mismatch: max_ack=${ACK_MAX} status=${ACK_STATUS} pointer=${POINTER_REV}"
                fi
            else
                log_skip "redis-cli unavailable; skipping ack key assertion"
            fi
        else
            log_fail "Publish job did not reach acked (status=${STATUS})"
        fi
    else
        log_fail "publish submit failed: $JOB"
    fi
else
    log_skip "Ollama not reachable at ${OLLAMA}; skipping control publish chain"
fi

# ============================================================
log_step "Step 6: Cleanup"
cleanup
QA_ID=""
log_pass "Cleaned up qa library + injected snapshot"

# ============================================================
echo ""
echo -e "${BLUE}============================================================${NC}"
if [ "${FAIL}" -gt 0 ]; then
    echo -e "${RED}QA reply E2E: ${PASS} passed, ${FAIL} failed${NC}"
    exit 1
fi
echo -e "${GREEN}QA reply E2E: ${PASS} passed, 0 failed${NC}"
