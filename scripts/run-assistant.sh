#!/usr/bin/env bash
# 启动 VirbiusLLM 安全专家 Agent（FastMCP + REST API + MCP Proxy）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LOG_DIR="${LOG_DIR:-/tmp/virbius/logs}"
mkdir -p "$LOG_DIR"

# ── 启动前清理旧进程 ──────────────────────────────
# 杀掉占用 9090/9091/9093 端口的残留进程，避免 "Address already in use"
pre_cleanup() {
  local ports=(9090 9091 9093)
  for port in "${ports[@]}"; do
    local pids
    pids=$(lsof -ti :"$port" 2>/dev/null || true)
    if [[ -n "$pids" ]]; then
      echo "Killing stale process on port $port (pid: $(echo $pids | tr '\n' ' '))..."
      echo "$pids" | xargs kill -9 2>/dev/null || true
    fi
  done
  sleep 1
}
pre_cleanup

cleanup() {
  echo "Stopping services..."
  for pid in "${MCP_PID:-}" "${PROXY_PID:-}" "${API_PID:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ── Python 虚拟环境 ──────────────────────────────
VENV_DIR="$ROOT/virbius-assistant/.venv"
if [[ ! -f "$VENV_DIR/bin/activate" ]]; then
  echo "Creating venv & installing dependencies..."
  python3 -m venv "$VENV_DIR"
  "$VENV_DIR/bin/pip" install -e "$ROOT/virbius-assistant" -q
fi
PYTHON="$VENV_DIR/bin/python"

# 1. FastMCP Server → 9093
echo "Starting FastMCP Server on :9093 ..."
$PYTHON -m virbius_assistant --mode mcp --mcp-port 9093 > "$LOG_DIR/fastmcp.log" 2>&1 &
MCP_PID=$!
sleep 1
if ! kill -0 "$MCP_PID" 2>/dev/null; then
  echo "ERROR: FastMCP Server failed to start, check $LOG_DIR/fastmcp.log"
  cat "$LOG_DIR/fastmcp.log" | tail -5
  exit 1
fi

export VIRBIUS_AGENT_APP_ID="${VIRBIUS_AGENT_APP_ID:-Agent1}"

# 2. MCP Proxy → 9091（API 期望的端口），上游指向 9093
MCP_PROXY_BIN="${MCP_PROXY_BIN:-/Users/iseeyou/Projects/VirbiusAgent/target/release/virbius-mcp-proxy}"
if [[ ! -f "$MCP_PROXY_BIN" ]]; then
  echo "ERROR: virbius-mcp-proxy not found at $MCP_PROXY_BIN"
  exit 1
fi
echo "Starting MCP Proxy on :9091 → upstream :9093 ..."
VIRBIUS_REDIS_URL=127.0.0.1:6379 \
VIRBIUS_TRANSPORT=tcp://0.0.0.0:9091 \
VIRBIUS_UPSTREAM_URL=http://localhost:9093 \
VIRBIUS_UPSTREAM_SSE_PATH=/sse \
VIRBIUS_ENGINE_URL=http://localhost:8082 \
VIRBIUS_FALLBACK_POLICY=minimum_privilege \
"$MCP_PROXY_BIN" > "$LOG_DIR/mcp-proxy.log" 2>&1 &
PROXY_PID=$!
sleep 1
if ! kill -0 "$PROXY_PID" 2>/dev/null; then
  echo "ERROR: MCP Proxy failed to start, check $LOG_DIR/mcp-proxy.log"
  cat "$LOG_DIR/mcp-proxy.log" | tail -5
  exit 1
fi

# 3. REST API → 9090
echo "Starting REST API on :9090 ..."
$PYTHON -m virbius_assistant --mode api --api-port 9090 --mcp-port 9091 > "$LOG_DIR/assistant-api.log" 2>&1 &
API_PID=$!
sleep 1
if ! kill -0 "$API_PID" 2>/dev/null; then
  echo "ERROR: REST API failed to start, check $LOG_DIR/assistant-api.log"
  cat "$LOG_DIR/assistant-api.log" | tail -5
  exit 1
fi

echo ""
echo "Services started:"
echo "  FastMCP Server  http://localhost:9093/sse  (log: $LOG_DIR/fastmcp.log)"
echo "  MCP Proxy       http://localhost:9091/sse  (log: $LOG_DIR/mcp-proxy.log)"
echo "  REST API        http://localhost:9090      (log: $LOG_DIR/assistant-api.log)"
echo ""
echo "Tail all logs: tail -f $LOG_DIR/fastmcp.log $LOG_DIR/mcp-proxy.log $LOG_DIR/assistant-api.log"
echo "Stop: kill $MCP_PID $PROXY_PID $API_PID"

wait
