#!/usr/bin/env bash
# 本地启动 VirbiusLLM（engine + control + gateway-agent）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$("/usr/libexec/java_home" -v 17 2>/dev/null || true)}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${PATH:-}"
export VIRBIUS_DATA_DIR="${VIRBIUS_DATA_DIR:-$ROOT/data}"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/virbius-gateway-agent/target}"
export VIRBIUS_REDIS_PORT="${VIRBIUS_REDIS_PORT:-6379}"
export VIRBIUS_REDIS_URL="${VIRBIUS_REDIS_URL:-redis://127.0.0.1:${VIRBIUS_REDIS_PORT}}"
MVN="${MVN:-$HOME/.local/apache-maven-3.9.6/bin/mvn}"
AGENT_BIN="$CARGO_TARGET_DIR/release/virbius-gateway-agent"
LOG_DIR="${LOG_DIR:-/tmp/virbius/logs}"
REDIS_PID_FILE="${REDIS_PID_FILE:-/tmp/virbius/redis.pid}"
mkdir -p "$LOG_DIR" "$VIRBIUS_DATA_DIR" "$(dirname "$REDIS_PID_FILE")"

if [[ "${VIRBIUS_REBUILD_DB:-}" == "1" ]]; then
  echo "Rebuilding SQLite databases (delete + Spring sql.init on startup)..."
  rm -f "$VIRBIUS_DATA_DIR"/*.db
fi

kill_port() {
  local port=$1
  local pids
  pids=$(lsof -ti :"$port" 2>/dev/null || true)
  if [[ -z "$pids" ]]; then
    return 0
  fi
  echo "Stopping process(es) on port $port: $pids"
  # shellcheck disable=SC2086
  kill -9 $pids 2>/dev/null || true
  sleep 1
}

wait_http() {
  local url=$1
  local name=$2
  local i
  for i in $(seq 1 40); do
    if curl -sf "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "ERROR: $name did not become ready: $url"
  return 1
}

# Start Redis only when needed (does not use brew services / login items).
ensure_redis() {
  if [[ "${VIRBIUS_REDIS_SKIP:-}" == "1" ]]; then
    echo "Skipping Redis (VIRBIUS_REDIS_SKIP=1)"
    return 0
  fi
  if ! command -v redis-cli >/dev/null 2>&1; then
    echo "WARN: redis-cli not found; cumulative counters will be degraded."
    echo "      Install: brew install redis"
    return 0
  fi
  if redis-cli -p "$VIRBIUS_REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
    echo "Redis already running on port $VIRBIUS_REDIS_PORT"
    return 0
  fi
  if ! command -v redis-server >/dev/null 2>&1; then
    echo "WARN: redis-server not found; cumulative counters will be degraded."
    return 0
  fi
  echo "Starting Redis on port $VIRBIUS_REDIS_PORT (daemon, not brew services)..."
  redis-server \
    --daemonize yes \
    --port "$VIRBIUS_REDIS_PORT" \
    --bind 127.0.0.1 \
    --pidfile "$REDIS_PID_FILE" \
    --logfile "$LOG_DIR/redis.log" \
    --save ""
  local i
  for i in $(seq 1 20); do
    if redis-cli -p "$VIRBIUS_REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
      echo "Redis ready ($VIRBIUS_REDIS_URL)"
      return 0
    fi
    sleep 0.5
  done
  echo "ERROR: Redis did not respond on port $VIRBIUS_REDIS_PORT"
  echo "--- redis.log (tail) ---"
  tail -20 "$LOG_DIR/redis.log" 2>/dev/null || true
  return 1
}

if [[ ! -x "$MVN" ]]; then
  echo "Maven not found. Install to ~/.local/apache-maven-3.9.6 or set MVN=..."
  exit 1
fi

echo "Building..."
"$MVN" -q -pl virbius-engine,virbius-control -am package -DskipTests
cargo build --release --manifest-path "$ROOT/virbius-gateway-agent/Cargo.toml"

echo "Freeing ports 8080, 8082, 9070..."
for p in 8080 8082 9070; do
  kill_port "$p"
done

ensure_redis

echo "Starting services (logs: $LOG_DIR)"
nohup env VIRBIUS_DATA_DIR="$VIRBIUS_DATA_DIR" VIRBIUS_REDIS_URL="$VIRBIUS_REDIS_URL" \
  java -jar "$ROOT/virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar" \
  >"$LOG_DIR/engine.log" 2>&1 &
nohup env VIRBIUS_DATA_DIR="$VIRBIUS_DATA_DIR" VIRBIUS_REDIS_URL="$VIRBIUS_REDIS_URL" \
  java -jar "$ROOT/virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar" \
  >"$LOG_DIR/control.log" 2>&1 &

if ! wait_http "http://127.0.0.1:8080/api/v1/health" "virbius-control"; then
  echo "--- control.log (tail) ---"
  tail -30 "$LOG_DIR/control.log" || true
  if grep -q "Port 8080 was already in use" "$LOG_DIR/control.log" 2>/dev/null; then
    echo "Hint: another process still holds 8080. Run: lsof -i :8080"
  fi
  exit 1
fi

wait_http "http://127.0.0.1:8082/admin/health" "virbius-engine" || {
  echo "--- engine.log (tail) ---"
  tail -20 "$LOG_DIR/engine.log" || true
  exit 1
}

nohup env VIRBIUS_ENGINE_URL=http://127.0.0.1:8082 VIRBIUS_DATA_DIR="$VIRBIUS_DATA_DIR" \
  "$AGENT_BIN" >"$LOG_DIR/agent.log" 2>&1 &
wait_http "http://127.0.0.1:9070/health" "gateway-agent" || {
  echo "--- agent.log (tail) ---"
  tail -20 "$LOG_DIR/agent.log" || true
  exit 1
}

echo ""
echo "redis            $VIRBIUS_REDIS_URL  (log: $LOG_DIR/redis.log)"
echo "virbius-engine   http://127.0.0.1:8082"
echo "virbius-control  http://127.0.0.1:8080"
echo "  运营台         http://127.0.0.1:8080/ui"
echo "gateway-agent    http://127.0.0.1:9070"
echo ""
echo "Stop Redis started by this script: redis-cli -p $VIRBIUS_REDIS_PORT shutdown"
echo "Smoke: bash $ROOT/scripts/smoke-test.sh"
