#!/bin/bash
# ============================================================
# E2E test: QA proxy reply (安全知识代答) — M1a
#   1. Health check (control + engine + agent)
#   2. Control CRUD + validator rejection
#   3. Agent reply consume (exact / contains / regex / miss) via Redis-injected snapshot
#   4. Engine-deny suppression (QA candidate must not override a block)
#   5. Optional: full control publish chain when Ollama is reachable
#   6. Cleanup
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
    {"entry_id": "e-regex", "question": "台.*湾", "answer": "标准回答C", "match_mode": "regex", "priority": 5, "enabled": true}
  ]
}'

if command -v redis-cli >/dev/null 2>&1 && redis-cli ping >/dev/null 2>&1; then
    REV=$(redis-cli incr "virbius:qa:seq:${TENANT}")
    redis-cli set "virbius:artifacts:qa:${TENANT}:r${REV}:qa" "$SNAPSHOT" >/dev/null
    redis-cli hset "virbius:config:qa:${TENANT}" qa_revision "$REV" updated_at "$(date -u +%FT%TZ)" >/dev/null
    log_info "Injected snapshot via Redis (revision ${REV})"
else
    mkdir -p "$(dirname "${QA_FILE}")"
    printf '%s' "$SNAPSHOT" > "${QA_FILE}"
    log_info "Redis unavailable; injected snapshot via file ${QA_FILE}"
fi

if [ "${QA_POLL_SEC}" -le 1 ]; then
    sleep 3
else
    sleep $((QA_POLL_SEC + 2))
fi

R1=$(agent_eval "测试代答问题" "测试代答问题")
if [ "$(echo "$R1" | jq -r '.effective_action')" = "reply" ] && [ "$(echo "$R1" | jq -r '.reply_text')" = "标准回答A" ]; then
    log_pass "exact hit → reply"
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

# ============================================================
log_step "Step 4: Engine-deny style suppression (gateway deny keyword)"

R5=$(agent_eval "jailbreak 测试代答问题" "测试代答问题")
if [ "$(echo "$R5" | jq -r '.effective_action')" = "block" ]; then
    log_pass "QA candidate suppressed by gateway deny (block)"
else
    log_fail "expected block (deny before reply), got: $R5"
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
            sleep $((QA_POLL_SEC + 2))
            R6=$(agent_eval "测试代答问题" "测试代答问题")
            if [ "$(echo "$R6" | jq -r '.effective_action')" = "reply" ]; then
                log_pass "Redis-published snapshot consumed by agent"
            else
                log_fail "agent did not reply from published snapshot: $R6"
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
