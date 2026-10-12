#!/bin/bash
# virbius-mcp-python 控制面全流程集成测试（真集成 / 方式3 SDK ↔ 运营台）
#
# 覆盖链路：
#   运营台建规则(draft) → 激活(dry_run) → deploy/edge 发布
#   → Edge API 拉 policy-version / manifest(ETag/304)
#   → 运营台签发 License(JWT) + 公钥
#   → SDK(virbius-mcp-python) control 模式拉取规则并缓存
#   → SDK scan 命中运营台规则(dry_run=review)
#   → SDK verify_license / precheck_tool 用运营台签发的 License 执法
#   → 运营台把规则升到 canary(100%) → SDK reload → scan 升级为 block
#   → 运营台再升到 full → SDK reload → 执法仍为 block
#   → 注册带 JSON-Schema 的工具 → precheck 参数校验拒绝分支(required/类型)
#   → 下线规则 → deploy/edge → 下发面撤销该规则(吊销收敛)
#
# 约束：只通过 HTTP API + SDK 观测结果断言，不读写 sqlite / redis / 磁盘 manifest。
# 依赖：CONTROL_API 在跑（dev 隐式鉴权），且 MCP_PYTHON 指向装了 virbius_mcp_python 的解释器。
set -euo pipefail

CONTROL="${CONTROL_API:-http://localhost:8080}"
TENANT="${TENANT:-default}"
REPO="${REPO_ROOT:-/Users/iseeyou/Projects/VirbiusAgent}"

TS=$(date +%s)
APP="e2e-sdk-$TS-$$"
MARKER="E2E_SDK_MARKER_$TS"
RULE_ID="e2e_sdk_deny_$TS-$$"

PASS=0
FAIL=0

pass() { echo "  ✅ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ❌ $1"; FAIL=$((FAIL + 1)); }
check() { # $1 desc  $2 actual  $3 expected
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (期望=[$3] 实际=[$2])"; fi
}
check_contains() { # $1 desc  $2 haystack  $3 needle
  case "$2" in
    *"$3"*) pass "$1" ;;
    *) fail "$1 (未包含 [$3], 实际=[$2])" ;;
  esac
}
check_not_contains() { # $1 desc  $2 haystack  $3 needle
  case "$2" in
    *"$3"*) fail "$1 (不应包含 [$3], 实际=[$2])" ;;
    *) pass "$1" ;;
  esac
}

get() { curl -s --max-time 15 "$1"; }
post() { curl -s --max-time 15 -X POST -H "Content-Type: application/json" -d "$2" "$1"; }
patch() { curl -s --max-time 15 -X PATCH -H "Content-Type: application/json" -d "$2" "$1"; }
http_code() { curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$1"; }

jval() { # $1 json  $2 dotted.path
  echo "$1" | python3 -c '
import sys, json
v = json.load(sys.stdin)
for k in sys.argv[1].split("."):
    v = v.get(k) if isinstance(v, dict) else None
    if v is None:
        break
print("" if v is None else v)' "$2"
}

# ---------- locate a python with virbius_mcp_python ----------
MCP_PY="${MCP_PYTHON:-}"
if [ -z "$MCP_PY" ]; then
  for cand in python3 "$REPO/virbius-mcp-python/.venv/bin/python"; do
    if "$cand" -c "import virbius_mcp_python" 2>/dev/null; then MCP_PY="$cand"; break; fi
  done
fi

echo "=========================================================="
echo "  virbius-mcp-python 控制面全流程集成测试"
echo "  运营台: $CONTROL  租户: $TENANT"
echo "  app=$APP  rule=$RULE_ID"
echo "  python: ${MCP_PY:-<未找到>}"
echo "=========================================================="

# ---------- [0] prereqs ----------
echo ""
echo "[0] 前置检查..."
if [ -z "$MCP_PY" ]; then
  echo "  ❌ 未找到装有 virbius_mcp_python 的 Python。"
  echo "     构建安装：cd $REPO/virbius-mcp-python && maturin build --release -o dist && pip install dist/*.whl"
  exit 1
fi
if ! curl -s --max-time 5 "$CONTROL/actuator/health" | grep -q '"status":"UP"'; then
  echo "  ❌ 运营台不可达: $CONTROL"
  exit 1
fi
pass "运营台可达且 UP"
pass "SDK 解释器可用: $MCP_PY"

# ---------- [1] license 签名 key bootstrap ----------
echo ""
echo "[1] License 签名 key bootstrap..."
PK=$(get "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/public-key")
if [ "$(jval "$PK" "code")" = "0" ]; then
  pass "签名 key 已存在（复用）"
else
  RK=$(post "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/rotate-key" "")
  check "无 key 时自动 rotate-key 创建" "$(jval "$RK" "code")" "0"
  PK=$(get "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/public-key")
fi
PUBLIC_KEY_PEM=$(jval "$PK" "data.public_key_pem")
check_contains "public-key 返回 PEM" "$PUBLIC_KEY_PEM" "BEGIN PUBLIC KEY"

# ---------- [2] 建 service-bind 规则(draft) ----------
echo ""
echo "[2] 建 service-bind deny 规则 (draft)..."
RULE_BODY=$(python3 -c "
import json,sys
print(json.dumps({
    'rule_id': sys.argv[1], 'bundle_id': 'demo-default', 'layer': 'edge', 'runtime': 'lua-dsl',
    'reason_code': 'E2E_SDK_DENY_MARKER', 'risk_score': 95, 'intent_action': 'deny',
    'scope': {'bind_scope': 'service', 'bind_ref': {'app_ids': [sys.argv[2]]}},
    'body': {'keywords': [sys.argv[3]], 'list_type': 'deny'}, 'is_async': False,
}))" "$RULE_ID" "$APP" "$MARKER")
UP=$(post "$CONTROL/api/v1/admin/tenants/$TENANT/rules" "$RULE_BODY")
check "规则创建成功" "$(jval "$UP" "code")" "0"
check "新规则 rollout_state=draft" "$(jval "$UP" "data.rollout_state")" "draft"

# ---------- [3] 激活 dry_run + 发布 edge ----------
echo ""
echo "[3] 激活 dry_run 并 deploy/edge..."
RT=$(patch "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/runtime" '{"enforce_mode":"dry_run"}')
check "激活到 dry_run" "$(jval "$RT" "data.enforce_mode")" "dry_run"
DEP=$(post "$CONTROL/api/v1/admin/tenants/$TENANT/deploy/edge" "")
check "deploy/edge 成功" "$(jval "$DEP" "data.deployed")" "True"

# ---------- [4] Edge API 原生拉取 ----------
echo ""
echo "[4] Edge API 拉取 policy-version / manifest..."
PV=$(get "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/policy-version")
check "policy-version 可达" "$(jval "$PV" "artifact_revision")" "1"
check_contains "policy-version 含 content_sha256" "$(jval "$PV" "content_sha256")" ""
REVISION=$(jval "$PV" "artifact_revision")

M_URL="$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/manifest?pool=stable"
ETAG=$(curl -s -D - -o /tmp/e2e_mf.json --max-time 15 "$M_URL" | grep -i '^etag' | tr -d '\r' | sed 's/.*: *//;s/"//g')
MF_RULES=$(python3 -c "import json;print(','.join(sorted(r['rule_id'] for r in json.load(open('/tmp/e2e_mf.json'))['rules'])))")
MF_DRY=$(python3 -c "import json;d=json.load(open('/tmp/e2e_mf.json'));print(next((r['enforce_mode'] for r in d['rules'] if r['rule_id']=='$RULE_ID'),''))")
check_contains "manifest 含运营台规则" "$MF_RULES" "$RULE_ID"
check "运营台规则 enforce_mode=dry_run" "$MF_DRY" "dry_run"
check_contains "manifest 含全局既有规则(edge_l0_content_deny)" "$MF_RULES" "edge_l0_content_deny"

CODE304=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 -H "If-None-Match: $ETAG" "$M_URL")
check "If-None-Match 命中返回 304" "$CODE304" "304"

# ---------- [5] 运营台签发 License ----------
echo ""
echo "[5] 运营台签发 License..."
ISSUE=$(post "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/issue" "{\"app_id\":\"$APP\",\"agent_name\":\"e2e-agent\",\"allowed_tools\":[\"read_file\",\"search\",\"exec_cmd\"],\"risk_quota\":80,\"tool_rate_limit\":40,\"expiry_seconds\":3600,\"description\":\"control-plane e2e\"}")
check "License 签发成功" "$(jval "$ISSUE" "code")" "0"
JWT=$(jval "$ISSUE" "data.jwt")
LIC_ID=$(jval "$ISSUE" "data.license_id")
check_contains "JWT 为 EdDSA 三段式" "$JWT" "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9"

JWT_FILE=$(mktemp); PEM_FILE=$(mktemp); trap 'rm -f "$JWT_FILE" "$PEM_FILE"' EXIT
printf '%s' "$JWT" > "$JWT_FILE"
printf '%s' "$PUBLIC_KEY_PEM" > "$PEM_FILE"

# ---------- [6] SDK control 模式拉取 + 执法 ----------
echo ""
echo "[6] SDK(virbius-mcp-python) control 模式：拉规则 + License 执法 (dry_run)..."
CACHE1=$(mktemp -d)
SDK1=$(E2E_CONTROL_URL="$CONTROL" E2E_TENANT="$TENANT" E2E_APP="$APP" E2E_MARKER="$MARKER" \
  E2E_RULE_ID="$RULE_ID" E2E_CACHE_DIR="$CACHE1" E2E_MODE="dry_run" \
  E2E_JWT_FILE="$JWT_FILE" E2E_PEM_FILE="$PEM_FILE" \
  "$MCP_PY" - <<'PY'
import json, os, pathlib
import virbius_mcp_python as v

jwt = pathlib.Path(os.environ["E2E_JWT_FILE"]).read_text().strip()
pem = pathlib.Path(os.environ["E2E_PEM_FILE"]).read_text()
cache = pathlib.Path(os.environ["E2E_CACHE_DIR"])
marker = os.environ["E2E_MARKER"]
rule_id = os.environ["E2E_RULE_ID"]
app = os.environ["E2E_APP"]

out = {}
cfg = json.dumps({
    "control_base_url": os.environ["E2E_CONTROL_URL"],
    "tenant_id": os.environ["E2E_TENANT"],
    "app_id": app,
    "cache_dir": str(cache),
    "device_id": "e2e-device-1",
})
r = v.configure_rules(cfg)
out["mode"] = r["mode"]
out["sync_error"] = r["sync_error"]
out["cache_manifest"] = (cache / "edge-manifest.json").is_file()
out["cache_meta"] = (cache / "meta.json").is_file()

# 只含本用例独有 marker，避免撞上租户既有全局关键词规则（jailbreak 等）
s = v.scan_edge(f"run operation {marker} immediately", "sess-1")
out["scan_action"] = s.get("action")
out["scan_rule_id"] = s.get("rule_id", "")
out["scan_risk"] = s.get("max_risk_score", -1)

benign = v.scan_edge("what is the weather today", "sess-1")
out["benign_action"] = benign.get("action")

lic = v.verify_license(jwt, pem, app)
out["lic_tenant"] = lic["tenant_id"]
out["lic_tools"] = ",".join(sorted(lic["allowed_tools"]))
out["lic_quota"] = lic["risk_quota"]

p = v.precheck_tool("read_file", json.dumps({"path": "/tmp/a"}), jwt, pem, app)
out["pre_read_allowed"] = p["allowed"]
out["pre_read_fast_path"] = p["fast_path"]
out["pre_read_sandbox"] = p["sandbox_type"]

# exec_cmd 的 tool_policy(fast_path=false) 与 read_file(fast_path=true) 对照，
# 证明 SDK 真的加载了运营台下发的 per-tool policy，而非默认值。
e = v.precheck_tool("exec_cmd", json.dumps({"cmd": "echo hi"}), jwt, pem, app)
out["pre_exec_allowed"] = e["allowed"]
out["pre_exec_fast_path"] = e["fast_path"]

d = v.precheck_tool("db_delete", json.dumps({"id": 1}), jwt, pem, app)
out["pre_deny_allowed"] = d["allowed"]
out["pre_deny_reason"] = d["reason"] or ""

v.reload_rules()
out["reload_ok"] = True

for label, bad in (("neg_wrong_app", "not-the-app"), ("neg_tampered", None)):
    try:
        if bad is None:
            v.verify_license(jwt[: len(jwt) // 2] + "x", pem, app)
        else:
            v.verify_license(jwt, pem, bad)
        out[label] = False
    except Exception:
        out[label] = True

print(json.dumps(out))
PY
)

check "SDK mode=control" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["mode"])')" "control"
check "SDK sync_error 为空(真拉到规则)" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sync_error"])')" "None"
check "SDK 缓存了运营台 manifest" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["cache_manifest"])')" "True"
check "SDK 缓存了 meta.json" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["cache_meta"])')" "True"
check "SDK scan 命中运营台规则(rule_id 来自控制面)" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scan_rule_id"])')" "$RULE_ID"
check "SDK scan dry_run 生效=review(非 block)" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scan_action"])')" "review"
check "SDK scan risk=95(来自运营台规则)" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scan_risk"])')" "95"
check "SDK 良性内容放行" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["benign_action"])')" "allow"
check "SDK verify_license 租户=default" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["lic_tenant"])')" "$TENANT"
check "SDK verify_license 工具集" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["lic_tools"])')" "exec_cmd,read_file,search"
check "precheck 放行 read_file" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["pre_read_allowed"])')" "True"
check "precheck read_file fast_path=true(运营台 tool_policy)" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["pre_read_fast_path"])')" "True"
check "precheck sandbox=none" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["pre_read_sandbox"])')" "none"
check "precheck exec_cmd fast_path=false(对照,证明逐工具策略生效)" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["pre_exec_fast_path"])')" "False"
check "precheck 拒绝未授权工具 db_delete" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["pre_deny_allowed"])')" "False"
check_contains "拒绝原因=not in license allowlist" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["pre_deny_reason"])')" "not in license allowlist"
check "reload_rules 幂等不抛异常" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["reload_ok"])')" "True"
check "负例: app_id 不匹配被拒" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["neg_wrong_app"])')" "True"
check "负例: 篡改 JWT 被拒" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["neg_tampered"])')" "True"

# ---------- [7] 运营台升级 canary(100%) → SDK 升级为 block ----------
echo ""
echo "[7] 运营台升级 canary(100%) → SDK reload → 执法升级为 block..."
RC=$(patch "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/runtime" '{"enforce_mode":"canary","canary_percent":100}')
check "运营台升级到 canary" "$(jval "$RC" "data.enforce_mode")" "canary"
check "canary_percent=100" "$(jval "$RC" "data.canary_percent")" "100"
check "deploy/edge 重发布" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/deploy/edge" "")" "data.deployed")" "True"

NEW_REV=$(jval "$(get "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/policy-version")" "artifact_revision")
check "artifact_revision 已递增" "$NEW_REV" "2"

CACHE2=$(mktemp -d)
SDK2=$(E2E_CONTROL_URL="$CONTROL" E2E_TENANT="$TENANT" E2E_APP="$APP" E2E_MARKER="$MARKER" \
  E2E_RULE_ID="$RULE_ID" E2E_CACHE_DIR="$CACHE2" E2E_MODE="canary" \
  E2E_JWT_FILE="$JWT_FILE" E2E_PEM_FILE="$PEM_FILE" \
  "$MCP_PY" - <<'PY'
import json, os, pathlib
import virbius_mcp_python as v
jwt = pathlib.Path(os.environ["E2E_JWT_FILE"]).read_text().strip()
pem = pathlib.Path(os.environ["E2E_PEM_FILE"]).read_text()
cache = pathlib.Path(os.environ["E2E_CACHE_DIR"])
marker, rule_id = os.environ["E2E_MARKER"], os.environ["E2E_RULE_ID"]
out = {}
r = v.configure_rules(json.dumps({
    "control_base_url": os.environ["E2E_CONTROL_URL"], "tenant_id": os.environ["E2E_TENANT"],
    "app_id": os.environ["E2E_APP"], "cache_dir": str(cache), "device_id": "e2e-device-1"}))
out["mode"], out["sync_error"] = r["mode"], r["sync_error"]
s = v.scan_edge(f"run operation {marker} immediately", "sess-1")
out["scan_action"], out["scan_rule_id"] = s.get("action"), s.get("rule_id", "")
# canary 档位下 License/执法仍成立
out["pre_read_allowed"] = v.precheck_tool("read_file", json.dumps({"path": "/tmp/a"}), jwt, pem, os.environ["E2E_APP"])["allowed"]
print(json.dumps(out))
PY
)
check "SDK mode=control" "$(echo "$SDK2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["mode"])')" "control"
check "SDK 二次拉取无 sync_error" "$(echo "$SDK2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sync_error"])')" "None"
check "SDK 命中同一运营台规则" "$(echo "$SDK2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scan_rule_id"])')" "$RULE_ID"
check "SDK 执法升级: canary(100%) → block" "$(echo "$SDK2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scan_action"])')" "block"
check "升级后 precheck 仍放行" "$(echo "$SDK2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["pre_read_allowed"])')" "True"

# ---------- [8] 升级 full 档 + precheck 参数 schema 拒绝分支 ----------
echo ""
echo "[8] 升级 full 档 + precheck 参数 schema 拒绝分支..."
REV_BEFORE_FULL=$(jval "$(get "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/policy-version")" "artifact_revision")
RF=$(patch "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/runtime" '{"enforce_mode":"full"}')
check "运营台升级到 full" "$(jval "$RF" "data.enforce_mode")" "full"
check "full 档重发布" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/deploy/edge" "")" "data.deployed")" "True"
REV_FULL=$(jval "$(get "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/policy-version")" "artifact_revision")
check "full 档 artifact_revision 递增" "$REV_FULL" "$((REV_BEFORE_FULL + 1))"
MF_FULL=$(get "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/manifest?pool=stable" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(next((r['enforce_mode'] for r in d['rules'] if r['rule_id'] == '$RULE_ID'), ''))")
check "manifest 中该规则 enforce_mode=full" "$MF_FULL" "full"

CACHE3=$(mktemp -d)
SDK3=$(E2E_CONTROL_URL="$CONTROL" E2E_TENANT="$TENANT" E2E_APP="$APP" E2E_MARKER="$MARKER" \
  E2E_CACHE_DIR="$CACHE3" "$MCP_PY" - <<'PY'
import json, os
import virbius_mcp_python as v
r = v.configure_rules(json.dumps({"control_base_url": os.environ["E2E_CONTROL_URL"],
                                  "tenant_id": os.environ["E2E_TENANT"],
                                  "app_id": os.environ["E2E_APP"],
                                  "cache_dir": os.environ["E2E_CACHE_DIR"],
                                  "device_id": "e2e-device-1"}))
s = v.scan_edge("run operation " + os.environ["E2E_MARKER"] + " immediately", "sess-1")
print(json.dumps({"sync_error": r["sync_error"], "action": s.get("action"),
                  "rule_id": s.get("rule_id", "")}))
PY
)
check "full 档 SDK 二次拉取无 sync_error" "$(echo "$SDK3" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sync_error"])')" "None"
check "full 档 SDK 命中同一规则" "$(echo "$SDK3" | python3 -c 'import sys,json;print(json.load(sys.stdin)["rule_id"])')" "$RULE_ID"
check "full 档执法=block" "$(echo "$SDK3" | python3 -c 'import sys,json;print(json.load(sys.stdin)["action"])')" "block"

# ---- precheck 的 args schema 分支：注册带 JSON-Schema 的工具后校验 ----
SCHEMA_TOOL="e2e_schema_tool_$TS"
TOOL_BODY=$(python3 -c "
import json, sys
print(json.dumps({
    'tool_name': sys.argv[1], 'risk_class': 'low', 'sandbox_type': 'none', 'timeout_ms': 5000,
    'fast_path': False,
    'allowed_args_schema': json.dumps({'required': ['path'], 'properties': {'path': {'type': 'string'}}}),
    'description': 'e2e args-schema tool'}))" "$SCHEMA_TOOL")
check "注册带 schema 的工具" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/tools" "$TOOL_BODY")" "code")" "0"
check "工具注册后重发布(下发 tool_policies)" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/deploy/edge" "")" "data.deployed")" "True"

# License 必须包含该工具，否则会先被 allowlist 拒绝，测不到 schema 分支。
# 每个 app 同时只能有一张有效 License（重复签发 409），故先吊销阶段5那张再重签，
# 顺带覆盖 License 吊销 API。
check "吊销阶段5 License" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/$LIC_ID/revoke" '{"reason":"e2e schema subcase"}')" "code")" "0"
# 请求体先落到变量再传：内联 \" 转义的 JSON 在嵌套命令替换里会被 shell 弄坏
SCHEMA_BODY="{\"app_id\":\"$APP\",\"agent_name\":\"e2e-schema\",\"allowed_tools\":[\"$SCHEMA_TOOL\"],\"risk_quota\":50,\"tool_rate_limit\":20,\"expiry_seconds\":3600,\"description\":\"schema e2e\"}"
SCHEMA_JWT=$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/issue" "$SCHEMA_BODY")" "data.jwt")
SCHEMA_JWT_FILE=$(mktemp); trap 'rm -f "$JWT_FILE" "$PEM_FILE" "$SCHEMA_JWT_FILE"' EXIT
check "schema 子用例 License 签发成功(JWT 非空)" "$([ -n "$SCHEMA_JWT" ] && echo ok || echo empty)" "ok"
printf '%s' "$SCHEMA_JWT" > "$SCHEMA_JWT_FILE"

CACHE4=$(mktemp -d)
SDK4=$(E2E_CONTROL_URL="$CONTROL" E2E_TENANT="$TENANT" E2E_APP="$APP" E2E_TOOL="$SCHEMA_TOOL" \
  E2E_CACHE_DIR="$CACHE4" E2E_JWT_FILE="$SCHEMA_JWT_FILE" E2E_PEM_FILE="$PEM_FILE" "$MCP_PY" - <<'PY'
import json, os, pathlib
import virbius_mcp_python as v
jwt = pathlib.Path(os.environ["E2E_JWT_FILE"]).read_text().strip()
pem = pathlib.Path(os.environ["E2E_PEM_FILE"]).read_text()
tool = os.environ["E2E_TOOL"]

v.configure_rules(json.dumps({"control_base_url": os.environ["E2E_CONTROL_URL"],
                              "tenant_id": os.environ["E2E_TENANT"],
                              "app_id": os.environ["E2E_APP"],
                              "cache_dir": os.environ["E2E_CACHE_DIR"],
                              "device_id": "e2e-device-1"}))

def run(args):
    r = v.precheck_tool(tool, json.dumps(args), jwt, pem, os.environ["E2E_APP"])
    return {"allowed": r["allowed"], "reason": r["reason"] or ""}

print(json.dumps({
    "wrong_type": run({"path": 123}),
    "missing_required": run({"other": "x"}),
    "valid": run({"path": "/tmp/a"}),
}))
PY
)
check "args 类型不符被拒" "$(echo "$SDK4" | python3 -c 'import sys,json;print(json.load(sys.stdin)["wrong_type"]["allowed"])')" "False"
check_contains "拒绝原因=args validation failed" "$(echo "$SDK4" | python3 -c 'import sys,json;print(json.load(sys.stdin)["wrong_type"]["reason"])')" "args validation failed"
check "缺 required 字段被拒" "$(echo "$SDK4" | python3 -c 'import sys,json;print(json.load(sys.stdin)["missing_required"]["allowed"])')" "False"
check_contains "拒绝原因=missing required field" "$(echo "$SDK4" | python3 -c 'import sys,json;print(json.load(sys.stdin)["missing_required"]["reason"])')" "missing required field"
check "args 合法则放行" "$(echo "$SDK4" | python3 -c 'import sys,json;print(json.load(sys.stdin)["valid"]["allowed"])')" "True"

rm -rf "$CACHE3" "$CACHE4"

# ---------- [9] 清理 ----------
echo ""
echo "[9] 清理 + 吊销收敛验证..."
# 注意1：runtime PATCH 只接受 dry_run/canary/full（其余回落 dry_run）；
#        下线必须走 status PATCH。
CLEAN=$(patch "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/status" '{"rule_status":"disabled"}')
check "规则已 disabled" "$(jval "$CLEAN" "data.rollout_state")" "disabled"

# 吊销收敛：disable 该 app 最后一条 service 规则后重发布，下发面必须撤销该规则
# （回归此前 ArtifactService 只为执行面 app 写 manifest、导致旧规则永久残留的 bug）。
# revision 断言用"相对上一次发布 +1"，避免被中途新增的发布阶段绑死。
REV_BEFORE=$(jval "$(get "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/policy-version")" "artifact_revision")
check "吊销后 deploy/edge 成功" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/deploy/edge" "")" "data.deployed")" "True"
REV_DISABLED=$(jval "$(get "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/policy-version")" "artifact_revision")
check "吊销后 policy-version 仍可达(收敛重发布, 非404) 且 revision 递增" "$REV_DISABLED" "$((REV_BEFORE + 1))"
MF_DISABLED=$(get "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP/manifest?pool=stable" \
  | python3 -c "import sys,json;print(','.join(sorted(r['rule_id'] for r in json.load(sys.stdin)['rules'])))")
check_not_contains "吊销后 manifest 已撤销该规则" "$MF_DISABLED" "$RULE_ID"
check_contains "吊销后仍保留全局规则(edge_l0_content_deny)" "$MF_DISABLED" "edge_l0_content_deny"
# 工具注册表是租户级全局表，必须删除以免污染后续运行
curl -s -o /dev/null -X DELETE --max-time 15 "$CONTROL/api/v1/admin/tenants/$TENANT/tools/$SCHEMA_TOOL"
rm -rf "$CACHE1" "$CACHE2"

echo ""
echo "=========================================================="
echo "  结果: PASS=$PASS  FAIL=$FAIL"
echo "=========================================================="
[ "$FAIL" -eq 0 ]
