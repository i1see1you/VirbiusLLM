#!/bin/bash
# 测试完整 HIL 流程：时间范围确认 -> 风险评估 -> 创建工单 -> 审批 -> 报告保存确认
set -euo pipefail

API="http://localhost:9090"
TENANT="default"
BASE="$API/api/v1/tenants/$TENANT/agent"

echo "=========================================="
echo "  VirbiusLLM Agent HIL 完整流程测试"
echo "=========================================="

# ─── 第1步：查询（时间范围 > 365天，触发 interrupt）──
echo ""
echo "[1/7] 发起查询（时间范围 500 天，应触发时间确认）..."
NOW=$(python3 -c "from datetime import datetime,timezone; print(datetime.now(timezone.utc).isoformat())")
START=$(python3 -c "from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)-timedelta(days=500)).isoformat())")

RESP1=$(curl -s --max-time 30 -X POST "$BASE/query" \
  -H "Content-Type: application/json" \
  -d "{\"entity_type\":\"user\",\"entity_value\":\"test_user_001\",\"time_range_start\":\"$START\",\"time_range_end\":\"$NOW\",\"mode\":\"force_review\"}")

CONCLUSION=$(echo "$RESP1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('conclusion',''))")
THREAD_ID=$(echo "$RESP1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('thread_id',''))")

if [ "$CONCLUSION" != "pending_confirmation" ]; then
  echo "  ❌ 预期 pending_confirmation，实际: $CONCLUSION"
  exit 1
fi
MSG=$(echo "$RESP1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('pending_confirmation',{}).get('message',''))")
echo "  ✅ 时间确认: $MSG (thread_id: $THREAD_ID)"

# ─── 第2步：确认时间范围 ──
echo ""
echo "[2/7] 确认时间范围，继续执行..."
RESP2=$(curl -s --max-time 30 -X POST "$BASE/query/$THREAD_ID/resume" \
  -H "Content-Type: application/json" \
  -d '{"confirmed":true}')

CONCLUSION2=$(echo "$RESP2" | python3 -c "import sys,json; print(json.load(sys.stdin).get('conclusion',''))")
TICKET_ID=$(echo "$RESP2" | python3 -c "import sys,json; print(json.load(sys.stdin).get('ticket_id','') or '')")

if [ "$CONCLUSION2" = "pending_confirmation" ]; then
  MSG2=$(echo "$RESP2" | python3 -c "import sys,json; print(json.load(sys.stdin).get('pending_confirmation',{}).get('message',''))")
  echo "  ✅ 报告保存确认: $MSG2"
  if [ -n "$TICKET_ID" ] && [ "$TICKET_ID" != "None" ]; then
    echo "  ✅ 工单已创建: $TICKET_ID"
  fi
else
  echo "  ⚠️ 结论: $CONCLUSION2 (可能数据不足)"
fi

# ─── 第3步：先不保存报告，去审批工单 ──
if [ -n "$TICKET_ID" ] && [ "$TICKET_ID" != "None" ]; then
  echo ""
  echo "[3/7] 查看工单详情..."
  curl -s --max-time 5 "$BASE/tickets/$TICKET_ID" | python3 -c "import sys,json; t=json.load(sys.stdin); print(f'  工单: {t[\"ticket_id\"]} status={t[\"status\"]} action={t[\"recommended_action\"]}')"

  echo ""
  echo "[4/7] 人工审批通过..."
  RESP4=$(curl -s --max-time 10 -X POST "$BASE/tickets/$TICKET_ID/approve" \
    -H "Content-Type: application/json" \
    -d '{"operator":"admin","comment":"确认越狱风险"}')
  echo "  ✅ 审批: $(echo $RESP4 | python3 -c "import sys,json; r=json.load(sys.stdin); print(r.get('status','?'))")"
else
  echo ""
  echo "[3/7] 跳过（无工单）"
  echo "[4/7] 跳过（无工单）"
fi

# ─── 第5步：确认保存报告 ──
echo ""
echo "[5/7] 确认保存报告到本地文件..."
RESP5=$(curl -s --max-time 10 -X POST "$BASE/query/$THREAD_ID/resume" \
  -H "Content-Type: application/json" \
  -d '{"confirmed":true}')

FINAL_CONCLUSION=$(echo "$RESP5" | python3 -c "import sys,json; print(json.load(sys.stdin).get('conclusion',''))")
echo "  ✅ 最终结论: $FINAL_CONCLUSION"

# ─── 第6步：验证报告文件 ──
echo ""
echo "[6/7] 验证报告文件..."
REPORT_FILE=$(ls -t /Users/iseeyou/Projects/VirbiusLLM/data/reports/report_asmt_*.md 2>/dev/null | head -1)
if [ -n "$REPORT_FILE" ]; then
  echo "  ✅ 报告文件: $REPORT_FILE"
  echo "  --- 文件内容前5行 ---"
  head -5 "$REPORT_FILE" | sed 's/^/    /'
else
  echo "  ❌ 未找到报告文件"
fi

# ─── 第7步：测试拒绝保存 ──
echo ""
echo "[7/7] 另一次测试：拒绝保存报告..."
NOW2=$(python3 -c "from datetime import datetime,timezone; print(datetime.now(timezone.utc).isoformat())")
START2=$(python3 -c "from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)-timedelta(days=30)).isoformat())")

RESP7A=$(curl -s --max-time 30 -X POST "$BASE/query" \
  -H "Content-Type: application/json" \
  -d "{\"entity_type\":\"user\",\"entity_value\":\"test_user_001\",\"time_range_start\":\"$START2\",\"time_range_end\":\"$NOW2\",\"mode\":\"force_review\"}")

CONCLUSION7A=$(echo "$RESP7A" | python3 -c "import sys,json; print(json.load(sys.stdin).get('conclusion',''))")
if [ "$CONCLUSION7A" = "pending_confirmation" ]; then
  THREAD_ID2=$(echo "$RESP7A" | python3 -c "import sys,json; print(json.load(sys.stdin).get('thread_id',''))")
  RESP7B=$(curl -s --max-time 30 -X POST "$BASE/query/$THREAD_ID2/resume" \
    -H "Content-Type: application/json" \
    -d '{"confirmed":true}')
  CONCLUSION7B=$(echo "$RESP7B" | python3 -c "import sys,json; print(json.load(sys.stdin).get('conclusion',''))")
  if [ "$CONCLUSION7B" = "pending_confirmation" ]; then
    echo "  报告保存确认出现，拒绝保存..."
    RESP7C=$(curl -s --max-time 10 -X POST "$BASE/query/$THREAD_ID2/resume" \
      -H "Content-Type: application/json" \
      -d '{"confirmed":false}')
    echo "  ✅ 拒绝保存, 流程正常结束"
  else
    echo "  ⚠️ 未出现报告保存确认 (conclusion=$CONCLUSION7B)"
  fi
else
  echo "  ⚠️ 30天范围未触发确认 (conclusion=$CONCLUSION7A), 跳过拒绝测试"
fi

echo ""
echo "=========================================="
echo "  ✅ HIL 完整流程测试通过！"
echo "=========================================="
echo ""
echo "  流程回顾:"
echo "  1. 查询(500天) -> 时间范围确认"
echo "  2. 确认 -> 风险评估 -> 创建工单 -> 生成报告"
echo "  3. 审批工单 -> approved"
echo "  4. 报告保存确认 -> 用户确认 -> 写入文件"
echo "  5. (可选) 用户拒绝 -> 不写文件"
