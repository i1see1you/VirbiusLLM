#!/usr/bin/env bash
# Point OpenClaw LLM traffic: OpenClaw agent → Virbius OpenResty :9080 → Ollama :11434
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VIRBIUS_URL="${VIRBIUS_OPENRESTY_URL:-http://127.0.0.1:9080/v1}"
APP_ID="${VIRBIUS_APP_ID:-beta}"
PRIMARY_MODEL="${OPENCLAW_PRIMARY_MODEL:-ollama/qwen3.5:9b}"
BATCH="$ROOT/integrations/openclaw/openclaw-virbius.batch.json"

if ! command -v openclaw >/dev/null 2>&1; then
  echo "ERROR: openclaw CLI not found (brew install openclaw / npm i -g openclaw)"
  exit 1
fi

if [[ ! -f "$BATCH" ]]; then
  echo "ERROR: missing $BATCH"
  exit 1
fi

# Substitute env-specific values into batch file
TMP_BATCH="$(mktemp)"
trap 'rm -f "$TMP_BATCH"' EXIT
sed \
  -e "s|__VIRBIUS_URL__|$VIRBIUS_URL|g" \
  -e "s|__APP_ID__|$APP_ID|g" \
  -e "s|__PRIMARY_MODEL__|$PRIMARY_MODEL|g" \
  "$BATCH" >"$TMP_BATCH"

echo "Applying OpenClaw Virbius integration ..."
openclaw config set --batch-file "$TMP_BATCH"

echo ""
echo "OpenClaw LLM provider 'ollama' → $VIRBIUS_URL (OpenResty + Virbius)"
echo "Default agent model → $PRIMARY_MODEL"
echo "Virbius header X-App-Id → $APP_ID"
echo ""
echo "Restart OpenClaw gateway:"
echo "  launchctl kickstart -k gui/\$(id -u)/ai.openclaw.gateway"
echo "  # or: openclaw gateway --port 18789"
