#!/bin/bash
# 原生工具集成 A+B 联合全流程集成测试
#
# 闭环：同一 app 同时启用
#   模式 A（virbius-integrate / LangChain callback 观测）
#   模式 B（virbius-mcp-python / control 模式执法）
# 断言两者并存互不干扰：观测事件持续落库，执法决策随运营台状态切换。
#
# 与既有两个脚本的分工：
#   test_integrate_full_flow.sh      —— 模式 A 控制面契约（HTTP 直连模拟）
#   test_mcp_python_control_e2e.sh   —— 模式 B SDK ↔ 运营台执法链路
#   本脚本                          —— A+B 同 agent 联合闭环（产品主路径）
#
# 约束：只通过 HTTP API + SDK/插件观测结果断言，不读写 sqlite / redis / 磁盘。
# 依赖：CONTROL_API 在跑（dev 隐式鉴权）。
set -euo pipefail

CONTROL="${CONTROL_API:-http://localhost:8080}"
TENANT="${TENANT:-default}"
REPO="${REPO_ROOT:-/Users/iseeyou/Projects/VirbiusAgent}"
INT_PY="${INTEGRATE_PYTHON:-$REPO/virbius-integrate/.venv/bin/python}"
MCP_PY="${MCP_PYTHON:-}"

TS=$(date +%s)
APP="e2e-ab-$TS-$$"
RULE_ID="e2e_ab_deny_$TS-$$"
OBS_TOOL="get_weather"

PASS=0
FAIL=0

pass() { echo "  ✅ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ❌ $1"; FAIL=$((FAIL + 1)); }
check() { # $1 desc  $2 actual  $3 expected
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (期望=[$3] 实际=[$2])"; fi
}

get() { curl -s --max-time 15 "$1"; }
post() { curl -s --max-time 15 -X POST -H "Content-Type: application/json" -d "$2" "$1"; }
patch() { curl -s --max-time 15 -X PATCH -H "Content-Type: application/json" -d "$2" "$1"; }

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
jlen() { # $1 json  $2 list.path
  echo "$1" | python3 -c '
import sys, json
v = json.load(sys.stdin)
for k in sys.argv[1].split("."):
    v = v.get(k) if isinstance(v, dict) else None
    if v is None:
        break
print(0 if v is None else len(v))' "$2"
}

OBS_URL="$CONTROL/api/v1/admin/tenants/$TENANT/integrate/apps/$APP/observations"
COV_URL="$CONTROL/api/v1/admin/tenants/$TENANT/integrate/apps/$APP/coverage"

# ---------- locate interpreters ----------
if [ -z "$MCP_PY" ]; then
  for cand in python3 "$REPO/virbius-mcp-python/.venv/bin/python"; do
    if "$cand" -c "import virbius_mcp_python" 2>/dev/null; then MCP_PY="$cand"; break; fi
  done
fi

echo "=========================================================="
echo "  原生工具集成 A+B 联合全流程测试"
echo "  运营台: $CONTROL  租户: $TENANT  app=$APP"
echo "  integrate: ${INT_PY:-<未找到>}"
echo "  mcp:       ${MCP_PY:-<未找到>}"
echo "=========================================================="

# ---------- [0] prereqs ----------
echo ""
echo "[0] 前置检查..."
if ! curl -s --max-time 5 "$CONTROL/actuator/health" | grep -q '"status":"UP"'; then
  echo "  ❌ 运营台不可达: $CONTROL"; exit 1
fi
pass "运营台可达且 UP"

if [ ! -x "$INT_PY" ] || ! "$INT_PY" -c "import virbius_integrate, langchain_core" 2>/dev/null; then
  echo "  ❌ 未找到装有 virbius-integrate + langchain-core 的 Python: $INT_PY"; exit 1
fi
pass "模式 A 解释器可用: $INT_PY"

if [ -z "$MCP_PY" ]; then
  echo "  ❌ 未找到装有 virbius_mcp_python 的 Python（模式 B 缺失，无法做联合闭环）"
  echo "     构建安装：cd $REPO/virbius-mcp-python && maturin build --release -o dist && pip install dist/*.whl"
  exit 1
fi
pass "模式 B 解释器可用: $MCP_PY"

# ---------- [1] 启动握手（A 的接入自证）----------
echo ""
echo "[1] 模式 A 启动握手: 可达 / 不可达 对照..."
# 握手走独立 app，避免其探针事件混入主 app 的观测计数断言
HS_OK=$("$INT_PY" - "$CONTROL" "$TENANT" "${APP}-hs" <<'PY' 2>/dev/null
import json, sys
from virbius_integrate.callback import VirbiusAuditCallback
cb = VirbiusAuditCallback(app_id=sys.argv[3], tenant_id=sys.argv[2], endpoint=sys.argv[1],
                          handshake=False, heartbeat=False)
r = cb.handshake(deep=True)
print(json.dumps({"ok": r.ok, "stage": r.stage, "status": r.status, "deep": r.deep}))
cb.shutdown(timeout=2)
PY
)
check "握手 deep 成功 (ok)" "$(echo "$HS_OK" | python3 -c 'import sys,json;print(json.load(sys.stdin)["ok"])')" "True"
check "握手 deep 成功 (status=200)" "$(echo "$HS_OK" | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])')" "200"
check "握手 deep 成功 (deep=True 已走 ingest 回读)" "$(echo "$HS_OK" | python3 -c 'import sys,json;print(json.load(sys.stdin)["deep"])')" "True"

# 失败握手的 WARNING 会经 logging.lastResort 打到 stderr，必须与 JSON 分离采集
HS_NEG=$("$INT_PY" <<'PY' 2>/dev/null || true
import json
from virbius_integrate.callback import VirbiusAuditCallback
from virbius_integrate.handshake import VirbiusIntegrateError
try:
    VirbiusAuditCallback(app_id="x", tenant_id="y",
                         endpoint="http://127.0.0.1:1", strict=True, heartbeat=False)
    print(json.dumps({"raised": False, "stage": ""}))
except VirbiusIntegrateError as e:
    print(json.dumps({"raised": True, "stage": e.result.stage, "status": e.result.status}))
PY
)
check "不可达 + strict=True 抛 VirbiusIntegrateError" "$(echo "$HS_NEG" | python3 -c 'import sys,json;print(json.load(sys.stdin)["raised"])')" "True"
check "失败停在 reach 阶段（定位连接层而非鉴权/路由）" "$(echo "$HS_NEG" | python3 -c 'import sys,json;print(json.load(sys.stdin)["stage"])')" "reach"

# ---------- [2] 模式 A: 真实 LangChain 入口观测 ----------
echo ""
echo "[2] 模式 A: 真实 LangChain Tool.invoke + callback 观测落库..."
BEFORE=$(jlen "$(get "$OBS_URL")" "events")
A_OUT=$("$INT_PY" - "$CONTROL" "$TENANT" "$APP" "$OBS_TOOL" <<'PY' 2>&1
import json, sys
from types import SimpleNamespace
from langchain_core.tools import tool
from virbius_integrate.callback import VirbiusAuditCallback

tool_name = sys.argv[4]

@tool
def probe_tool(city: str) -> str:
    """weather lookup used by the joint e2e"""
    return f"{city}: 21C"
probe_tool.name = tool_name

cb = VirbiusAuditCallback(app_id=sys.argv[3], tenant_id=sys.argv[2], endpoint=sys.argv[1],
                          tools=[SimpleNamespace(name=tool_name, description="weather lookup",
                                                 args={"city": "SH"})],
                          mcp_names={tool_name: "mcp." + tool_name},
                          heartbeat=False, handshake=False)
out = probe_tool.invoke({"city": "SH"}, config={"callbacks": [cb]})
cb.shutdown(timeout=5)
print(json.dumps({"invoke_result": out}))
PY
)
check "真实 LangChain 调用返回业务结果" "$(echo "$A_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["invoke_result"])')" "SH: 21C"

# 等待后台 inventory 线程完成上报后再断言 coverage
sleep 2
AFTER=$(jlen "$(get "$OBS_URL")" "events")
check "观测事件经真实回调入口落库 (+2)" "$((AFTER - BEFORE))" "2"

PAIR=$(get "$OBS_URL" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
by = {}
for e in evs:
    by.setdefault(e.get("call_id"), []).append(e.get("phase"))
paired = any(sorted(p) == ["completed", "started"] for p in by.values())
tool_seen = any(e.get("tool_name") == "'"$OBS_TOOL"'" for e in evs)
print("paired" if paired and tool_seen else f"unpaired({tool_seen})")')
check "started/completed 成对且带工具名" "$PAIR" "paired"

COV=$(get "$COV_URL")
check "coverage 已采集到被观测工具" "$(echo "$COV" | python3 -c '
import sys, json
print("'$OBS_TOOL'" in [t["tool_name"] for t in json.load(sys.stdin).get("tools", [])])')" "True"

# ---------- [3] 模式 B: 同 app 建执法规则 + SDK 同步 ----------
echo ""
echo "[3] 模式 B: 同 app 建 deny 规则(关键词=被观测工具) → dry_run → SDK 执法..."
RULE_BODY=$(python3 -c "
import json,sys
print(json.dumps({
    'rule_id': sys.argv[1], 'bundle_id': 'demo-default', 'layer': 'edge', 'runtime': 'lua-dsl',
    'reason_code': 'E2E_AB_DENY', 'risk_score': 90, 'intent_action': 'deny',
    'scope': {'bind_scope': 'service', 'bind_ref': {'app_ids': [sys.argv[2]]}},
    'body': {'keywords': [sys.argv[3]], 'list_type': 'deny'}, 'is_async': False,
}))" "$RULE_ID" "$APP" "$OBS_TOOL")
check "规则创建成功" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/rules" "$RULE_BODY")" "code")" "0"
check "激活 dry_run" "$(jval "$(patch "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/runtime" '{"enforce_mode":"dry_run"}')" "data.enforce_mode")" "dry_run"
check "deploy/edge 成功" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/deploy/edge" "")" "data.deployed")" "True"

CACHE1=$(mktemp -d)
SDK1=$(E2E_CONTROL_URL="$CONTROL" E2E_TENANT="$TENANT" E2E_APP="$APP" E2E_TOOL="$OBS_TOOL" \
  E2E_RULE_ID="$RULE_ID" E2E_CACHE_DIR="$CACHE1" "$MCP_PY" - <<'PY'
import json, os, pathlib
import virbius_mcp_python as v
cfg = json.dumps({"control_base_url": os.environ["E2E_CONTROL_URL"],
                  "tenant_id": os.environ["E2E_TENANT"],
                  "app_id": os.environ["E2E_APP"],
                  "cache_dir": os.environ["E2E_CACHE_DIR"],
                  "device_id": "e2e-ab-device"})
r = v.configure_rules(cfg)
s = v.scan_edge("please use the " + os.environ["E2E_TOOL"] + " now", "sess-ab")
print(json.dumps({"mode": r["mode"], "sync_error": r["sync_error"],
                  "action": s.get("action"), "rule_id": s.get("rule_id", "")}))
PY
)
check "SDK mode=control" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["mode"])')" "control"
check "SDK 命中运营台规则(执法生效)" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["rule_id"])')" "$RULE_ID"
check "dry_run → review（不阻断）" "$(echo "$SDK1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["action"])')" "review"

# ---------- [4] 闭环升级: canary → block，且观测不中断 ----------
echo ""
echo "[4] 闭环: 升级 canary(100%) → 执法 block；同时观测持续落库..."
check "升级 canary" "$(jval "$(patch "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/runtime" '{"enforce_mode":"canary","canary_percent":100}')" "data.enforce_mode")" "canary"
check "重发布" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/deploy/edge" "")" "data.deployed")" "True"

CACHE2=$(mktemp -d)
SDK2=$(E2E_CONTROL_URL="$CONTROL" E2E_TENANT="$TENANT" E2E_APP="$APP" E2E_TOOL="$OBS_TOOL" \
  E2E_RULE_ID="$RULE_ID" E2E_CACHE_DIR="$CACHE2" "$MCP_PY" - <<'PY'
import json, os
import virbius_mcp_python as v
v.configure_rules(json.dumps({"control_base_url": os.environ["E2E_CONTROL_URL"],
                              "tenant_id": os.environ["E2E_TENANT"],
                              "app_id": os.environ["E2E_APP"],
                              "cache_dir": os.environ["E2E_CACHE_DIR"],
                              "device_id": "e2e-ab-device"}))
s = v.scan_edge("please use the " + os.environ["E2E_TOOL"] + " now", "sess-ab")
print(json.dumps({"action": s.get("action"), "rule_id": s.get("rule_id", "")}))
PY
)
check "执法升级: canary(100%) → block" "$(echo "$SDK2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["action"])')" "block"

BEFORE2=$(jlen "$(get "$OBS_URL")" "events")
"$INT_PY" - "$CONTROL" "$TENANT" "$APP" "$OBS_TOOL" <<'PY' >/dev/null 2>&1
import sys
from langchain_core.tools import tool
from virbius_integrate.callback import VirbiusAuditCallback

@tool
def probe_tool(city: str) -> str:
    """second run while enforcement is blocking"""
    return f"{city}: 19C"
probe_tool.name = sys.argv[4]

cb = VirbiusAuditCallback(app_id=sys.argv[3], tenant_id=sys.argv[2], endpoint=sys.argv[1],
                          heartbeat=False, handshake=False)
probe_tool.invoke({"city": "BJ"}, config={"callbacks": [cb]})
cb.shutdown(timeout=5)
PY
sleep 1
AFTER2=$(jlen "$(get "$OBS_URL")" "events")
check "执法 block 期间观测仍持续落库 (A 不受 B 影响)" "$((AFTER2 - BEFORE2))" "2"

# ---------- [5] 吊销收敛: 规则下线后执法放开，观测不中断 ----------
echo ""
echo "[5] 吊销: disable 规则 → 执法放开；观测仍正常..."
check "规则 disabled" "$(jval "$(patch "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/status" '{"rule_status":"disabled"}')" "data.rollout_state")" "disabled"
check "重发布" "$(jval "$(post "$CONTROL/api/v1/admin/tenants/$TENANT/deploy/edge" "")" "data.deployed")" "True"

CACHE3=$(mktemp -d)
SDK3=$(E2E_CONTROL_URL="$CONTROL" E2E_TENANT="$TENANT" E2E_APP="$APP" E2E_TOOL="$OBS_TOOL" \
  E2E_RULE_ID="$RULE_ID" E2E_CACHE_DIR="$CACHE3" "$MCP_PY" - <<'PY'
import json, os
import virbius_mcp_python as v
v.configure_rules(json.dumps({"control_base_url": os.environ["E2E_CONTROL_URL"],
                              "tenant_id": os.environ["E2E_TENANT"],
                              "app_id": os.environ["E2E_APP"],
                              "cache_dir": os.environ["E2E_CACHE_DIR"],
                              "device_id": "e2e-ab-device"}))
s = v.scan_edge("please use the " + os.environ["E2E_TOOL"] + " now", "sess-ab")
print(json.dumps({"action": s.get("action"), "rule_id": s.get("rule_id", ""), "marker_present": os.environ["E2E_TOOL"] in s.get("rule_id", "")}))
PY
)
check "吊销后 manifest 已撤销该规则" "$(echo "$SDK3" | python3 -c 'import sys,json;print(json.load(sys.stdin)["rule_id"])')" ""
check "吊销后执法放开 → allow" "$(echo "$SDK3" | python3 -c 'import sys,json;print(json.load(sys.stdin)["action"])')" "allow"

BEFORE3=$(jlen "$(get "$OBS_URL")" "events")
"$INT_PY" - "$CONTROL" "$TENANT" "$APP" "$OBS_TOOL" <<'PY' >/dev/null 2>&1
import sys
from langchain_core.tools import tool
from virbius_integrate.callback import VirbiusAuditCallback

@tool
def probe_tool(city: str) -> str:
    """third run after revocation"""
    return f"{city}: 7C"
probe_tool.name = sys.argv[4]

cb = VirbiusAuditCallback(app_id=sys.argv[3], tenant_id=sys.argv[2], endpoint=sys.argv[1],
                          heartbeat=False, handshake=False)
probe_tool.invoke({"city": "GZ"}, config={"callbacks": [cb]})
cb.shutdown(timeout=5)
PY
sleep 1
AFTER3=$(jlen "$(get "$OBS_URL")" "events")
check "吊销后观测仍持续落库" "$((AFTER3 - BEFORE3))" "2"

TOTAL=$(jlen "$(get "$OBS_URL")" "events")
check "观测总事件=6 (三次真实 LangChain 调用, 各两点)" "$TOTAL" "6"

rm -rf "$CACHE1" "$CACHE2" "$CACHE3"

echo ""
echo "=========================================================="
echo "  结果: PASS=$PASS  FAIL=$FAIL"
echo "  联合 app: $APP (同一 app 同时挂 A 观测 + B 执法)"
echo "=========================================================="
[ "$FAIL" -eq 0 ]
