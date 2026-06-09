#!/usr/bin/env bash
# Reset audit ingest checkpoint so virbius-control backfills Redis Stream -> tb_audit_events on startup.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="${VIRBIUS_DATA_DIR:-$ROOT/data}"
STREAM_KEY="${VIRBIUS_AUDIT_STREAM_KEY:-virbius:audit:events}"
DB="${VIRBIUS_CONTROL_DB:-$DATA_DIR/virbius-control.db}"

if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "ERROR: sqlite3 not found"
  exit 1
fi
if [[ ! -f "$DB" ]]; then
  echo "ERROR: control database not found: $DB"
  exit 1
fi

sqlite3 "$DB" "DELETE FROM tb_audit_ingest_checkpoint WHERE stream_key='$STREAM_KEY';"
echo "Deleted ingest checkpoint for stream: $STREAM_KEY"
echo ""
echo "Restart virbius-control to backfill (from stream start if no checkpoint):"
echo "  bash $ROOT/scripts/run-local.sh"
echo ""
echo "Then verify:"
echo "  sqlite3 $DB \"SELECT COUNT(*) FROM tb_audit_events;\""
echo "  curl -s http://127.0.0.1:8080/api/v1/admin/tenants/default/audit/ingest-status"
