#!/usr/bin/env bash
# OpenClaw ↔ VirbiusLLM 集成配置：
#   1. LLM 流量: OpenClaw agent → Virbius OpenResty :9080 → Ollama（供审计用）
#   2. Skill:      安装 security-assessment skill，通过 MCP 调用 virbius-assistant 工具
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

OPENCLAW_SKILL_SOURCE="$ROOT/virbius-assistant/skills/security-assessment"
OPENCLAW_SKILL_NAME="assess-risk"

# OpenClaw workspace skills 目录（最高优先级）
if [[ -n "${OPENCLAW_WORKSPACE:-}" ]]; then
  SKILL_TARGET="$OPENCLAW_WORKSPACE/skills/$OPENCLAW_SKILL_NAME"
else
  SKILL_TARGET="$HOME/.openclaw/workspace/skills/$OPENCLAW_SKILL_NAME"
fi

# ── 前置检查 ──────────────────────────────────────────

if ! command -v openclaw >/dev/null 2>&1; then
  echo "ERROR: openclaw CLI not found (brew install openclaw / npm i -g openclaw)"
  exit 1
fi

if [[ ! -d "$OPENCLAW_SKILL_SOURCE" ]]; then
  echo "ERROR: skill source not found at $OPENCLAW_SKILL_SOURCE"
  exit 1
fi

# ── 1. 安装 Security Assessment Skill ─────────────────

echo "Installing VirbiusLLM security-assessment skill ..."
mkdir -p "$(dirname "$SKILL_TARGET")"

if [[ -L "$SKILL_TARGET" ]] || [[ -d "$SKILL_TARGET" ]]; then
  echo "  Skill already exists at $SKILL_TARGET, replacing ..."
  rm -rf "$SKILL_TARGET"
fi

ln -sf "$OPENCLAW_SKILL_SOURCE" "$SKILL_TARGET"
echo "  ✅ Symlink created: $SKILL_TARGET → $OPENCLAW_SKILL_SOURCE"

echo ""
echo "  Skill installed: /$OPENCLAW_SKILL_NAME"
echo "  Trigger phrases: 'security assessment', '查一下风险', 'assess risk', etc."
echo ""

# ── 2. [可选] 配置 LLM Provider ──────────────────────
# 指向 Virbius OpenResty，使 OpenClaw 的 LLM 流量经过审计

VIRBIUS_URL="${VIRBIUS_OPENRESTY_URL:-http://127.0.0.1:9080/v1}"
APP_ID="${VIRBIUS_APP_ID:-beta}"
PRIMARY_MODEL="${OPENCLAW_PRIMARY_MODEL:-ollama/qwen3.5:9b}"
BATCH="$ROOT/integrations/openclaw/openclaw-virbius.batch.json"

if [[ -f "$BATCH" ]]; then
  TMP_BATCH="$(mktemp)"
  trap 'rm -f "$TMP_BATCH"' EXIT
  sed \
    -e "s|__VIRBIUS_URL__|$VIRBIUS_URL|g" \
    -e "s|__APP_ID__|$APP_ID|g" \
    -e "s|__PRIMARY_MODEL__|$PRIMARY_MODEL|g" \
    "$BATCH" >"$TMP_BATCH"

  echo "Applying OpenClaw Virbius LLM provider config ..."
  openclaw config set --batch-file "$TMP_BATCH"

  echo "  OpenClaw LLM provider 'ollama' → $VIRBIUS_URL (OpenResty + Virbius)"
  echo "  Default agent model → $PRIMARY_MODEL"
  echo "  Virbius header X-App-Id → $APP_ID"
else
  echo "  (Skipped LLM provider config: $BATCH not found)"
fi

# ── 3. 验证与提示 ────────────────────────────────────

echo ""
echo "Verifying skill installation ..."
openclaw skills list 2>/dev/null | grep -q "$OPENCLAW_SKILL_NAME" && \
  echo "  ✅ /$OPENCLAW_SKILL_NAME loaded" || \
  echo "  ⚠️  Not yet loaded — start a new session or restart gateway"

echo ""
echo "Next steps:"
echo "  1. Ensure MCP server is configured in openclaw.json:"
echo '     "mcp": { "servers": { "virbius-assistant": { "url": "http://localhost:9091/sse" } } }'
echo "  2. Ensure virbius-assistant is running (see scripts/run-assistant.sh)"
echo "  3. Restart OpenClaw gateway to pick up changes:"
echo "     launchctl kickstart -k gui/\$(id -u)/ai.openclaw.gateway"
echo "     # or: openclaw gateway restart"
echo "  4. Try: openclaw agent --message '查一下用户 user_123 的风险'"
