#!/usr/bin/env bash
# ============================================================
# 壳层全流程集成测试（运营台 Vue3 SPA ↔ 真实 control）
#   1. 构建 virbius-control（含 SPA，frontend-maven-plugin）
#   2. 以 dev profile 启动 control（临时端口 + 临时数据目录，免鉴权）
#   3. 对真实 SPA 跑 Playwright 集成用例（frontend/e2e/integration）
#   4. 清理
#
# 覆盖：QA 建库→条目保存→发布 acked；QA/规则脏守卫拦截；租户数据隔离。
# 前置：JDK17、Maven、Node20+ 与已安装的 Playwright chromium。
# 用法：bash scripts/test-ops-console-e2e.sh
#   环境变量：OPS_E2E_PORT（默认 18091）
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${OPS_E2E_PORT:-18091}"
DATA_DIR="$(mktemp -d "${TMPDIR:-/tmp}/virbius-ops-e2e.XXXXXX")"
JAR="$ROOT/virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar"
LOG="/tmp/ops-console-e2e-control.log"
PID=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  rm -rf "$DATA_DIR"
}
trap cleanup EXIT

echo "== [1/4] 构建 virbius-control（含 SPA）=="
if ! mvn -q -pl virbius-control -am package -DskipTests -o 2>/dev/null; then
  mvn -q -pl virbius-control -am package -DskipTests
fi

echo "== [2/4] 启动 control :${PORT}（dev，数据目录 ${DATA_DIR}）=="
java -jar "$JAR" --spring.profiles.active=dev --virbius.data-dir="$DATA_DIR" --server.port="$PORT" >"$LOG" 2>&1 &
PID=$!

ready=0
for _ in $(seq 1 40); do
  if curl -sf "http://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1; then ready=1; break; fi
  if ! kill -0 "$PID" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "control 未就绪（见 ${LOG}）"; tail -20 "$LOG"; exit 1
fi
echo "control 就绪：http://127.0.0.1:${PORT}/ui"

echo "== [3/4] Playwright 集成（真实后端）=="
cd "$ROOT/virbius-control/frontend"
if [ ! -d node_modules ]; then npm ci; fi
if [ -n "${CI:-}" ]; then
  npx playwright install --with-deps chromium
else
  npx playwright install chromium >/dev/null 2>&1 || true
fi
OPS_BASE_URL="http://127.0.0.1:$PORT" npx playwright test --config playwright.integration.config.ts

echo "== [4/4] 通过 =="
