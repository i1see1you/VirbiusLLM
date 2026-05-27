#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p /tmp/virbius

echo "Building Java modules..."
(cd "$ROOT" && mvn -q -pl virbius-engine,virbius-control,virbius-compiler -am package -DskipTests)

echo "Starting virbius-engine :8082 / gRPC :50051"
java -jar "$ROOT/virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar" &
ENGINE_PID=$!

echo "Starting virbius-control :8080"
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
