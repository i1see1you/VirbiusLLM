#!/usr/bin/env bash
# 本地快速开发启动（与 run-local.sh 共享环境变量约定）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export VIRBIUS_DATA_DIR="${VIRBIUS_DATA_DIR:-$ROOT/data}"
export VIRBIUS_REDIS_URL="${VIRBIUS_REDIS_URL:-redis://127.0.0.1:6379}"
export VIRBIUS_LOG_DIR="${VIRBIUS_LOG_DIR:-/tmp/virbius/logs}"
export VIRBIUS_LOG_MAX_FILE_SIZE="${VIRBIUS_LOG_MAX_FILE_SIZE:-100MB}"
export VIRBIUS_LOG_MAX_HISTORY_DAYS="${VIRBIUS_LOG_MAX_HISTORY_DAYS:-30}"
export VIRBIUS_GATEWAY_ARTIFACT_ENABLED="${VIRBIUS_GATEWAY_ARTIFACT_ENABLED:-true}"
export VIRBIUS_GATEWAY_ARTIFACT_LOCAL_FALLBACK="${VIRBIUS_GATEWAY_ARTIFACT_LOCAL_FALLBACK:-false}"
mkdir -p "$VIRBIUS_DATA_DIR" "$VIRBIUS_LOG_DIR"

echo "Building Java modules..."
(cd "$ROOT" && mvn -q -pl virbius-engine,virbius-control,virbius-compiler -am package -DskipTests)

echo "Starting virbius-engine :8082 / gRPC :50051"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}" \
  VIRBIUS_DATA_DIR="$VIRBIUS_DATA_DIR" VIRBIUS_REDIS_URL="$VIRBIUS_REDIS_URL" \
  VIRBIUS_LOG_DIR="$VIRBIUS_LOG_DIR" VIRBIUS_LOG_MAX_FILE_SIZE="$VIRBIUS_LOG_MAX_FILE_SIZE" \
  VIRBIUS_LOG_MAX_HISTORY_DAYS="$VIRBIUS_LOG_MAX_HISTORY_DAYS" \
  java -jar "$ROOT/virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar" &
ENGINE_PID=$!

echo "Starting virbius-control :8080"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}" \
  VIRBIUS_DATA_DIR="$VIRBIUS_DATA_DIR" VIRBIUS_REDIS_URL="$VIRBIUS_REDIS_URL" \
  VIRBIUS_GATEWAY_ARTIFACT_ENABLED="$VIRBIUS_GATEWAY_ARTIFACT_ENABLED" \
  VIRBIUS_GATEWAY_ARTIFACT_LOCAL_FALLBACK="$VIRBIUS_GATEWAY_ARTIFACT_LOCAL_FALLBACK" \
  VIRBIUS_LOG_DIR="$VIRBIUS_LOG_DIR" VIRBIUS_LOG_MAX_FILE_SIZE="$VIRBIUS_LOG_MAX_FILE_SIZE" \
  VIRBIUS_LOG_MAX_HISTORY_DAYS="$VIRBIUS_LOG_MAX_HISTORY_DAYS" \
  java -jar "$ROOT/virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar" &
CONTROL_PID=$!

sleep 3

echo "Building virbius-gateway-agent (Rust)"
(cd "$ROOT/virbius-gateway-agent" && cargo build --release)
AGENT_BIN="$ROOT/virbius-gateway-agent/target/release/virbius-gateway-agent"

echo "Starting virbius-gateway-agent :9070"
VIRBIUS_ENGINE_URL=http://127.0.0.1:8082 "$AGENT_BIN" &
AGENT_PID=$!

sleep 1
echo "PIDs engine=$ENGINE_PID control=$CONTROL_PID agent=$AGENT_PID"
echo "Run: $ROOT/scripts/smoke-test.sh"
