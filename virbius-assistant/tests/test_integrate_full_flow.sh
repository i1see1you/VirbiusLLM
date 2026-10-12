#!/bin/bash
# 原生工具集成（Mode A 观测）全流程集成测试
#
# 流程：插件侧上报（heartbeat / tool-inventory / tool-observations）
#       → 运营台页面侧 API 校验（apps / observations / coverage / liveness / audit）
#       → 补充：真实插件客户端契约 + 真实 LangChain 回调入口 + 启动握手
#
# 约束：只通过 HTTP API 模拟真实操作链路，不读写 sqlite / redis。
# 运营台: CONTROL_API (默认 http://localhost:8080)
set -euo pipefail

CONTROL="${CONTROL_API:-http://localhost:8080}"
TENANT="${TENANT:-default}"
APP_ID="e2e-intg-$(date +%s)-$$"
HB_ONLY_APP="e2e-hbonly-$(date +%s)-$$"
PLUGIN_APP_ID="${APP_ID}-realplugin"
REAL_PLUGIN_PY="/Users/iseeyou/Projects/VirbiusAgent/virbius-integrate/.venv/bin/python"

BASE_ADMIN="$CONTROL/api/v1/admin/tenants/$TENANT/integrate"
BASE_INTERNAL="$CONTROL/api/v1/internal/integrate/tenants/$TENANT/apps/$APP_ID"

PASS=0
FAIL=0

pass() { echo "  ✅ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ❌ $1"; FAIL=$((FAIL + 1)); }
note() { echo "  ⚠️  $1"; }
check() { # $1 desc  $2 actual  $3 expected
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (期望=[$3] 实际=[$2])"; fi
}
check_contains() { # $1 desc  $2 haystack  $3 needle
  case "$2" in
    *"$3"*) pass "$1" ;;
    *) fail "$1 (未包含 [$3], 实际=[$2])" ;;
  esac
}

get() { curl -s --max-time 15 "$1"; }
post() { curl -s --max-time 15 -X POST -H "Content-Type: application/json" -d "$2" "$1"; }
http_code() { # $1 url  $2 body
  curl -s -o /dev/null -w "%{http_code}" --max-time 15 -X POST -H "Content-Type: application/json" -d "$2" "$1"
}

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

iso() { python3 -c "import sys,time;print(time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + int(sys.argv[1]))))" "$1"; }

echo "=========================================="
echo "  原生工具集成 全流程集成测试"
echo "  运营台: $CONTROL   app: $APP_ID"
echo "=========================================="

echo ""
echo "[0/15] 运营台可达性与空态基线..."
if ! curl -s --max-time 5 -o /dev/null -w "%{http_code}" "$BASE_ADMIN/apps" | grep -q "200"; then
  echo "  ❌ 运营台 API 不可达: $CONTROL"
  exit 1
fi
check "空态: 新 app 的 observations 计数为 0" "$(jlen "$(get "$BASE_ADMIN/apps/$APP_ID/observations")" "events")" "0"
check "空态: 新 app 的 coverage 工具数为 0" "$(jlen "$(get "$BASE_ADMIN/apps/$APP_ID/coverage")" "tools")" "0"
check "空态: 新 app 的 liveness 设备数为 0" "$(jlen "$(get "$BASE_ADMIN/apps/$APP_ID/liveness")" "devices")" "0"

echo ""
echo "[1/15] 插件侧: 上报心跳 (device-1)..."
HB_TS=$(iso 0)
RESP=$(post "$BASE_INTERNAL/heartbeat" "{\"device_id\":\"device-1\",\"package_version\":\"0.1.0\",\"mode\":\"observe\",\"dropped_total\":0,\"ts\":\"$HB_TS\"}")
check "心跳上报 ok" "$(jval "$RESP" "ok")" "True"

echo ""
echo "[2/15] 插件侧: 上报工具清单 (高危/中危/低危 各一)..."
INV=$(python3 - "$HB_TS" <<'PY'
import json, sys
now = sys.argv[1]
def row(name, risk, mode, rules, conf, mcp):
    return {"tool_name": name, "mcp_name": mcp, "risk_hint": risk, "matched_rules": rules,
            "confidence": conf, "recommended_mode": mode, "rule_table_version": "integrate-rules-v1",
            "package_version": "0.1.0", "bound_mode": "A"}
print(json.dumps({"tools": [
    row("shell_exec", "high", "B", ["verb:exec"], "high", "mcp.shell_exec"),
    row("fetch_url", "medium", "B", ["read-verb:fetch", "egress:url"], "medium", "mcp.fetch_url"),
    row("get_weather", "low", "A", [], "low", "mcp.get_weather"),
], "reported_at": now}))
PY
)
RESP=$(post "$BASE_INTERNAL/tool-inventory" "$INV")
check "工具清单 accepted=3" "$(jval "$RESP" "accepted")" "3"

echo ""
echo "[3/15] 运营台: coverage 工具盘点 + 覆盖警示..."
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/coverage")
check "coverage 工具数=3" "$(jlen "$RESP" "tools")" "3"
check "coverage 警示数=2 (B模式绑A)" "$(jlen "$RESP" "warnings")" "2"
check "shell_exec risk_class=high" "$(echo "$RESP" | python3 -c '
import sys, json
tools = json.load(sys.stdin)["tools"]
print(next((t["risk_class"] for t in tools if t["tool_name"] == "shell_exec"), ""))')" "high"
check "fetch_url recommended_mode=B" "$(echo "$RESP" | python3 -c '
import sys, json
tools = json.load(sys.stdin)["tools"]
print(next((t["recommended_mode"] for t in tools if t["tool_name"] == "fetch_url"), ""))')" "B"
check "get_weather 不在警示列表" "$(echo "$RESP" | python3 -c '
import sys, json
w = json.load(sys.stdin)["warnings"]
print("get_weather" in [t["tool_name"] for t in w])')" "False"
check "shell_exec matched_rules 落库为 CSV" "$(echo "$RESP" | python3 -c '
import sys, json
tools = json.load(sys.stdin)["tools"]
print(next((t["matched_rules"] for t in tools if t["tool_name"] == "shell_exec"), ""))')" "verb:exec"
check "bound_mode=自报 A" "$(echo "$RESP" | python3 -c '
import sys, json
tools = json.load(sys.stdin)["tools"]
print(all(t["bound_mode"] == "A" for t in tools))')" "True"

echo ""
echo "[4/15] 插件侧: 上报观测事件 (2设备/4次调用, 含错误路径)..."
T_OLD=$(iso -300)
T_OLD2=$(iso -298)
T_NEW=$(iso -10)
T_NEW2=$(iso -8)
SINCE=$(iso -120)
OBS=$(python3 - "$T_OLD" "$T_OLD2" "$T_NEW" "$T_NEW2" <<'PY'
import json, sys, uuid
t0, t0b, t1, t1b = sys.argv[1:5]
def ev(call, phase, tool, at, device="device-1", **kw):
    d = {"event_id": str(uuid.uuid4()), "call_id": call, "phase": phase, "device_id": device,
         "session_id": "sess-e2e", "trace_id": "tr-" + call, "tool_name": tool,
         "observed_at": at, "source": "langchain-native", "package_version": "0.1.0"}
    d.update(kw)
    return d
print(json.dumps({"events": [
    ev("call-2", "started", "shell_exec", t0),
    ev("call-2", "error", "shell_exec", t0b, error="Permission denied"),
    ev("call-1", "started", "get_weather", t1),
    ev("call-1", "completed", "get_weather", t1b, duration_ms=120,
       args_repr='{"city":"Shanghai"}', result_repr='{"temp":21}'),
    ev("call-3", "started", "fetch_url", t1, device="device-2"),
    ev("call-3", "completed", "fetch_url", t1b, device="device-2", duration_ms=340),
    ev("call-4", "started", "get_weather", t1b),
    ev("call-4", "completed", "get_weather", t1b, duration_ms=5),
]}))
PY
)
RESP=$(post "$BASE_INTERNAL/tool-observations/batch" "$OBS")
check "观测批次 accepted=8" "$(jval "$RESP" "accepted")" "8"

echo ""
echo "[5/15] 运营台: observations 全量列表..."
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations")
check "事件总数=8" "$(jlen "$RESP" "events")" "8"
check "按 observed_at 升序, 最旧的先返回" "$(echo "$RESP" | python3 -c '
import sys, json
print(json.load(sys.stdin)["events"][0]["call_id"])')" "call-2"
check_contains "观测备注标注 advisory" "$(jval "$RESP" "note")" "advisory"
check_contains "观测备注标注 not hash-chained" "$(jval "$RESP" "note")" "hash-chained"

echo ""
echo "[6/15] 运营台: call_id 过滤（两点成对可见）..."
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?call_id=call-1")
check "call-1 事件数=2 (started+completed)" "$(jlen "$RESP" "events")" "2"
check "completed 带 duration_ms=120" "$(echo "$RESP" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
c = next(e for e in evs if e["phase"] == "completed")
print(c.get("duration_ms"))')" "120"
check_contains "completed 带 args_repr 预览" "$(echo "$RESP" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
print(next(e for e in evs if e["phase"] == "completed").get("args_repr") or "")')" "city"
check "started 在 completed 之前" "$(echo "$RESP" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
print(evs[0]["phase"])')" "started"
RESP_ERR=$(get "$BASE_ADMIN/apps/$APP_ID/observations?call_id=call-2")
check "call-2 error 事件带 error 信息" "$(echo "$RESP_ERR" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
print(next(e for e in evs if e["phase"] == "error").get("error"))')" "Permission denied"
RESP_D2=$(get "$BASE_ADMIN/apps/$APP_ID/observations?call_id=call-3")
check "跨设备事件 device_id=device-2" "$(echo "$RESP_D2" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
print(sorted({e.get("device_id") for e in evs})[0])')" "device-2"

echo ""
echo "[7/15] 运营台: since/until 时间窗过滤..."
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?since=$SINCE")
check "since 窗口过滤掉 5 分钟前的 call-2 (剩 6 条)" "$(jlen "$RESP" "events")" "6"
check "since 过滤后最旧的是 call-1/call-4" "$(echo "$RESP" | python3 -c '
import sys, json
print("call-2" in [e["call_id"] for e in json.load(sys.stdin)["events"]])')" "False"
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?since=$T_OLD&until=$T_OLD2")
check "until 上界生效 (只留 call-2 的 2 条)" "$(jlen "$RESP" "events")" "2"
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?since=$T_NEW2&until=$T_OLD")
check "since>until 返回空" "$(jlen "$RESP" "events")" "0"

echo ""
echo "[8/15] 运营台: limit 参数..."
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?limit=2")
check "limit=2 只返回 2 条" "$(jlen "$RESP" "events")" "2"
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?limit=0")
check "limit=0 被夹到最小 1 条" "$(jlen "$RESP" "events")" "1"
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?limit=99999")
check "limit=99999 被夹到上限且不报错" "$(jlen "$RESP" "events")" "8"

echo ""
echo "[9/15] 运营台: liveness 双源活性..."
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/liveness")
check "活跃设备数=2" "$(jlen "$RESP" "devices")" "2"
check "device-1 心跳源→live" "$(echo "$RESP" | python3 -c '
import sys, json
d = next(x for x in json.load(sys.stdin)["devices"] if x["device_id"] == "device-1")
print(d["state"])')" "live"
check "device-1 last_seen 等于上报心跳时间" "$(echo "$RESP" | python3 -c '
import sys, json
d = next(x for x in json.load(sys.stdin)["devices"] if x["device_id"] == "device-1")
print(d["last_seen"])')" "$HB_TS"
check "device-1 无心跳但有事件→仍 live" "$(echo "$RESP" | python3 -c '
import sys, json
d = next(x for x in json.load(sys.stdin)["devices"] if x["device_id"] == "device-2")
print(d["state"] if d.get("last_seen") in (None, "") else "has-heartbeat")')" "live"
check "device-1 package_version/mode 上报透传" "$(echo "$RESP" | python3 -c '
import sys, json
d = next(x for x in json.load(sys.stdin)["devices"] if x["device_id"] == "device-1")
print(d.get("package_version"), d.get("mode"))')" "0.1.0 observe"

echo ""
echo "[10/15] 插件侧+运营台: 掉线设备→stale..."
STALE_TS=$(iso -3600)
post "$BASE_INTERNAL/heartbeat" "{\"device_id\":\"device-3\",\"package_version\":\"0.1.0\",\"mode\":\"observe\",\"ts\":\"$STALE_TS\"}" >/dev/null
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/liveness")
check "device-3 心跳过期且无事件→stale" "$(echo "$RESP" | python3 -c '
import sys, json
d = next(x for x in json.load(sys.stdin)["devices"] if x["device_id"] == "device-3")
print(d["state"])')" "stale"

echo ""
echo "[11/15] 运营台: 应用列表聚合..."
RESP=$(get "$BASE_ADMIN/apps")
check "app 出现在运营台应用列表" "$(echo "$RESP" | python3 -c '
import sys, json
apps = [a["app_id"] for a in json.load(sys.stdin)["apps"]]
print("'$APP_ID'" in apps)')" "True"
check "app last_seen 非空" "$(echo "$RESP" | python3 -c '
import sys, json
a = next(x for x in json.load(sys.stdin)["apps"] if x["app_id"] == "'$APP_ID'")
print(bool(a.get("last_seen")))')" "True"

echo ""
echo "[12/15] 运营台: 审计最近事件卡片..."
RESP=$(get "$CONTROL/api/v1/admin/tenants/$TENANT/audit/recent?limit=5")
check "audit/recent 返回 code=0 信封" "$(jval "$RESP" "code")" "0"
check "audit/recent 含 db_count 字段" "$(echo "$RESP" | python3 -c '
import sys, json
print("db_count" in json.load(sys.stdin).get("data", {}))')" "True"

echo ""
echo "[13/15] 插件侧健壮性: 非法批次/去重/非法 phase..."
CODE=$(http_code "$BASE_INTERNAL/tool-observations/batch" '{"foo":1}')
check "缺 events 字段→400" "$CODE" "400"
BIG=$(python3 -c '
import json, uuid
print(json.dumps({"events": [{"event_id": str(uuid.uuid4()), "call_id": "big", "phase": "started", "observed_at": "2026-01-01T00:00:00Z"} for _ in range(101)]}))')
CODE=$(http_code "$BASE_INTERNAL/tool-observations/batch" "$BIG")
check "批次超过 max-batch-size(100)→413" "$CODE" "413"
DUP_ID="dup-$(date +%s)-$$"
DUP=$(python3 - "$DUP_ID" "$T_NEW" <<'PY'
import json, sys
i, at = sys.argv[1], sys.argv[2]
print(json.dumps({"events": [
    {"event_id": i, "call_id": "call-dup", "phase": "started", "tool_name": "get_weather", "observed_at": at},
    {"event_id": i, "call_id": "call-dup", "phase": "started", "tool_name": "get_weather", "observed_at": at},
]}))
PY
)
RESP=$(post "$BASE_INTERNAL/tool-observations/batch" "$DUP")
check "同批次重复 event_id: accepted 反映落库数=1" "$(jval "$RESP" "accepted")" "1"
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?call_id=call-dup")
check "重复 event_id 落库去重后=1 条" "$(jlen "$RESP" "events")" "1"
BAD_PHASE=$(python3 -c '
import json, uuid
print(json.dumps({"events": [{"event_id": str(uuid.uuid4()), "call_id": "call-badphase", "phase": "boom", "observed_at": "2026-01-01T00:00:00Z"}]}))')
RESP=$(post "$BASE_INTERNAL/tool-observations/batch" "$BAD_PHASE")
BAD_ACCEPTED=$(jval "$RESP" "accepted")
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?call_id=call-badphase")
BAD_STORED=$(jlen "$RESP" "events")
check "非法 phase: 在入库前被拒 (accepted=0, 落库=0, 不再静默丢失/计数不实)" "$BAD_ACCEPTED/$BAD_STORED" "0/0"

echo ""
echo "[14/15] 租户隔离与未知 app 空态..."
check "未知 app 空态 observations=0" "$(jlen "$(get "$BASE_ADMIN/apps/e2e-unknown-app/observations")" "events")" "0"
check "未知 app 空态 coverage=0" "$(jlen "$(get "$BASE_ADMIN/apps/e2e-unknown-app/coverage")" "tools")" "0"
OTHER_TENANT_BASE="$CONTROL/api/v1/admin/tenants/e2e-other-tenant/integrate"
check "跨租户查询同一 app_id 不可见" "$(jlen "$(get "$OTHER_TENANT_BASE/apps/$APP_ID/observations")" "events")" "0"
check "跨租户应用列表不含本租户 app" "$(echo "$(get "$OTHER_TENANT_BASE/apps")" | python3 -c '
import sys, json
print("'$APP_ID'" in [a["app_id"] for a in json.load(sys.stdin)["apps"]])')" "False"

echo ""
echo "[15/15] 同秒两点事件的展示顺序 + 仅心跳应用可见性..."
RESP=$(get "$BASE_ADMIN/apps/$APP_ID/observations?call_id=call-4")
ORDER=$(echo "$RESP" | python3 -c '
import sys, json
print(",".join(e["phase"] for e in json.load(sys.stdin)["events"]))')
if [ "$ORDER" = "started,completed" ]; then
  pass "同 observed_at 的两点事件按 started→completed 排序"
else
  fail "同 observed_at 两点事件顺序异常: 实际=[$ORDER] (根因见报告: ORDER BY phase 字典序)"
fi
post "$CONTROL/api/v1/internal/integrate/tenants/$TENANT/apps/$HB_ONLY_APP/heartbeat" \
  "{\"device_id\":\"device-hb\",\"package_version\":\"0.1.0\",\"mode\":\"observe\",\"ts\":\"$HB_TS\"}" >/dev/null
RESP=$(get "$BASE_ADMIN/apps")
SEEN=$(echo "$RESP" | python3 -c '
import sys, json
print("'$HB_ONLY_APP'" in [a["app_id"] for a in json.load(sys.stdin)["apps"]])')
if [ "$SEEN" = "True" ]; then
  pass "仅心跳(尚无工具/事件)的应用也出现在运营台列表"
else
  fail "仅心跳的应用不出现在运营台列表（根因见报告: apps 聚合 SQL 未并入 liveness 表）"
fi

echo ""
echo "———— 补充: 真实插件客户端契约校验 ————"
if [ -x "$REAL_PLUGIN_PY" ] && "$REAL_PLUGIN_PY" -c "import virbius_integrate" 2>/dev/null; then
  RP_OUT=$("$REAL_PLUGIN_PY" - "$CONTROL" "$TENANT" "$PLUGIN_APP_ID" <<'PY' 2>&1 || true
import sys, time, uuid
from types import SimpleNamespace
from virbius_integrate import IntegrateConfig
from virbius_integrate.emitter import Emitter
from virbius_integrate.heartbeat import Heartbeat
from virbius_integrate.report import report_tools

cfg = IntegrateConfig(app_id=sys.argv[3], tenant_id=sys.argv[2],
                      endpoint=sys.argv[1], device_id="rp-device", mode="observe")
print("hb", Heartbeat(cfg, None).beat())
print("inv", report_tools([
    SimpleNamespace(name="shell_exec", description="run a shell command", args={"command": "ls"}),
    SimpleNamespace(name="get_weather", description="weather lookup", args={"city": "SH"}),
], cfg, mcp_names={"shell_exec": "mcp.shell_exec", "get_weather": "mcp.get_weather"}))
em = Emitter(cfg)
cid = "rp-" + str(uuid.uuid4())
now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
em.enqueue({"call_id": cid, "phase": "started", "tool_name": "get_weather", "observed_at": now,
            "args_repr": '{"city":"SH"}'})
em.enqueue({"call_id": cid, "phase": "completed", "tool_name": "get_weather", "observed_at": now,
            "duration_ms": 42})
em.shutdown(timeout=5)
print("call_id", cid)
PY
)
  echo "$RP_OUT" | sed 's/^/    /'
  RP_CALL=$(echo "$RP_OUT" | awk '/^call_id /{print $2}')
  check "真实插件: 心跳上报成功" "$(echo "$RP_OUT" | awk '/^hb /{print $2}')" "True"
  RESP=$(get "$CONTROL/api/v1/admin/tenants/$TENANT/integrate/apps/$PLUGIN_APP_ID/coverage")
  check "真实插件: 分类器判定 shell_exec=high/B" "$(echo "$RESP" | python3 -c '
import sys, json
tools = {t["tool_name"]: t for t in json.load(sys.stdin)["tools"]}
t = tools.get("shell_exec", {})
print(t.get("risk_class"), t.get("recommended_mode"), t.get("bound_mode"))')" "high B A"
  check "真实插件: get_weather=low/A" "$(echo "$RESP" | python3 -c '
import sys, json
tools = {t["tool_name"]: t for t in json.load(sys.stdin)["tools"]}
t = tools.get("get_weather", {})
print(t.get("risk_class"), t.get("recommended_mode"))')" "low A"
  if [ -n "$RP_CALL" ]; then
    RESP=$(get "$CONTROL/api/v1/admin/tenants/$TENANT/integrate/apps/$PLUGIN_APP_ID/observations?call_id=$RP_CALL")
    check "真实插件: Emitter 两点事件经服务端可见" "$(jlen "$RESP" "events")" "2"
    check "真实插件: 事件掩码后 args_repr 仍可见" "$(echo "$RESP" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
print(bool(next(e for e in evs if e["phase"] == "started").get("args_repr")))')" "True"
  fi
else
  note "未找到可导入的 virbius-integrate 环境，跳过真实插件契约校验"
fi

echo ""
echo "———— 补充: 真实 LangChain 回调入口 + 启动握手 ————"
# 上面的契约校验直接调用了 Emitter/Heartbeat/report_tools，未经过用户真正使用的入口。
# 这一段走 langchain_core 的 Tool.invoke(callbacks=[VirbiusAuditCallback])，即产品主路径。
LC_APP="${APP_ID}-lc"
if [ -x "$REAL_PLUGIN_PY" ] && "$REAL_PLUGIN_PY" -c "import virbius_integrate, langchain_core" 2>/dev/null; then
  LC_OUT=$("$REAL_PLUGIN_PY" - "$CONTROL" "$TENANT" "$LC_APP" "$APP_ID" <<'PY' 2>/dev/null || true
import json, sys
from types import SimpleNamespace
from langchain_core.tools import tool
from virbius_integrate.callback import VirbiusAuditCallback
from virbius_integrate.handshake import VirbiusIntegrateError

control, tenant, app, tool_name = sys.argv[1], sys.argv[2], sys.argv[3], "lc_probe_tool"
out = {}

# 握手正例：strict 构造即同步握手
try:
    cb_strict = VirbiusAuditCallback(app_id=app, tenant_id=tenant, endpoint=control,
                                     strict=True, handshake_deep=True, heartbeat=False)
    hs = cb_strict.handshake_state
    out["hs_ok"] = bool(hs and hs.ok)
    out["hs_status"] = hs.status if hs else -1
    out["hs_deep"] = hs.deep if hs else False
    cb_strict.shutdown(timeout=2)
except VirbiusIntegrateError:
    out["hs_ok"] = False
    out["hs_status"] = -1
    out["hs_deep"] = False

# 握手负例：不可达端点 + strict 必须抛错，且停在 reach
try:
    VirbiusAuditCallback(app_id="x", tenant_id="y", endpoint="http://127.0.0.1:1",
                         strict=True, heartbeat=False)
    out["hs_neg_raised"] = False
    out["hs_neg_stage"] = ""
except VirbiusIntegrateError as e:
    out["hs_neg_raised"] = True
    out["hs_neg_stage"] = e.result.stage

# 真实 LangChain 入口：Tool.invoke 驱动 on_tool_start/on_tool_end
@tool
def lc_probe_tool(city: str) -> str:
    """probe tool for the callback-entry e2e"""
    return f"{city}: 21C"
lc_probe_tool.name = tool_name

cb = VirbiusAuditCallback(app_id=app, tenant_id=tenant, endpoint=control,
                          tools=[SimpleNamespace(name=tool_name, description="probe",
                                                 args={"city": "SH"})],
                          mcp_names={tool_name: "mcp." + tool_name},
                          heartbeat=False, handshake=False)
out["invoke_result"] = lc_probe_tool.invoke({"city": "SH"}, config={"callbacks": [cb]})
cb.shutdown(timeout=5)
print(json.dumps(out))
PY
)
  echo "$LC_OUT" | sed 's/^/    /'
  check "真实入口: strict 构造握手成功" "$(echo "$LC_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hs_ok"])')" "True"
  check "真实入口: 握手 status=200" "$(echo "$LC_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hs_status"])')" "200"
  check "真实入口: 握手走了 deep 回读" "$(echo "$LC_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hs_deep"])')" "True"
  check "真实入口: 不可达+strict 抛错" "$(echo "$LC_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hs_neg_raised"])')" "True"
  check "真实入口: 失败停在 reach 阶段" "$(echo "$LC_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["hs_neg_stage"])')" "reach"
  check "真实入口: LangChain 业务结果返回" "$(echo "$LC_OUT" | python3 -c 'import sys,json;print(json.load(sys.stdin)["invoke_result"])')" "SH: 21C"

  # 后台 inventory 线程落库需要一点时间
  sleep 2
  LC_EVENTS=$(get "$CONTROL/api/v1/admin/tenants/$TENANT/integrate/apps/$LC_APP/observations")
  # 深握手会写入一条探针事件（source=langchain-healthcheck），与真实工具观测分开断言
  check "真实入口: 深握手探针事件已回读" "$(echo "$LC_EVENTS" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
print(any(e.get("source") == "langchain-healthcheck" for e in evs))')" "True"
  check "真实入口: 真实工具观测=2 (started+completed)" "$(echo "$LC_EVENTS" | python3 -c '
import sys, json
evs = [e for e in json.load(sys.stdin)["events"] if e.get("source") != "langchain-healthcheck"]
print(len(evs))')" "2"
  check "真实入口: 两点共用同一 call_id" "$(echo "$LC_EVENTS" | python3 -c '
import sys, json
evs = [e for e in json.load(sys.stdin)["events"] if e.get("source") != "langchain-healthcheck"]
print(len({e.get("call_id") for e in evs}))')" "1"
  check "真实入口: started 带被观测工具名" "$(echo "$LC_EVENTS" | python3 -c '
import sys, json
evs = json.load(sys.stdin)["events"]
print(any(e.get("tool_name") == "lc_probe_tool" for e in evs))')" "True"
  LC_COV=$(get "$CONTROL/api/v1/admin/tenants/$TENANT/integrate/apps/$LC_APP/coverage")
  check "真实入口: coverage 已采集到工具" "$(echo "$LC_COV" | python3 -c '
import sys, json
print("lc_probe_tool" in [t["tool_name"] for t in json.load(sys.stdin).get("tools", [])])')" "True"
else
  note "缺少 virbius-integrate + langchain-core 环境，跳过真实 LangChain 回调入口校验"
fi

echo ""
echo "=========================================="
echo "  结果: PASS=$PASS  FAIL=$FAIL"
echo "  测试数据 app: $APP_ID (API 无删除端点, 由保留任务清理)"
echo "=========================================="
[ "$FAIL" -eq 0 ]
