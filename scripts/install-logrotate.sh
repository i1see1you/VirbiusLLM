#!/usr/bin/env bash
# Render and optionally install logrotate config for Redis + OpenResty logs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

LOG_DIR="${VIRBIUS_LOG_DIR:-/tmp/virbius/logs}"
OPENRESTY_PREFIX="${OPENRESTY_PREFIX:-$ROOT/.openresty-poc}"
MAX_SIZE="${VIRBIUS_LOG_MAX_FILE_SIZE:-100MB}"
MAX_DAYS="${VIRBIUS_LOG_MAX_HISTORY_DAYS:-30}"
TEMPLATE="$ROOT/config/logrotate/virbius-poc.conf"
RENDERED="${TMPDIR:-/tmp}/virbius-logrotate.conf"

sed \
  -e "s|@VIRBIUS_LOG_DIR@|$LOG_DIR|g" \
  -e "s|@OPENRESTY_PREFIX@|$OPENRESTY_PREFIX|g" \
  -e "s|@VIRBIUS_LOG_MAX_FILE_SIZE@|$MAX_SIZE|g" \
  -e "s|@VIRBIUS_LOG_MAX_HISTORY_DAYS@|$MAX_DAYS|g" \
  "$TEMPLATE" >"$RENDERED"

echo "Rendered: $RENDERED"
cat "$RENDERED"

if [[ "${1:-}" == "--install" ]]; then
  if [[ "$(uname -s)" != "Darwin" && "$(id -u)" -ne 0 ]]; then
    echo "ERROR: --install requires root on Linux (sudo bash $0 --install)"
    exit 1
  fi
  DEST="/etc/logrotate.d/virbius-poc"
  if [[ "$(uname -s)" == "Darwin" ]]; then
    DEST="/usr/local/etc/logrotate.d/virbius-poc"
    sudo mkdir -p "$(dirname "$DEST")"
  fi
  sudo cp "$RENDERED" "$DEST"
  echo "Installed: $DEST"
  echo "Test: sudo logrotate -d $DEST"
fi
