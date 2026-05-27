#!/usr/bin/env bash
# 使用 sqlite3 CLI 初始化本地 *.db 文件（与 classpath db/*.sql 对齐，方言为 PG/MySQL/SQLite 通用）
# PostgreSQL/MySQL：勿用本脚本；由 Spring spring.sql.init 或自有迁移工具执行同一套 schema.sql/seed.sql
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="${VIRBIUS_DATA_DIR:-$ROOT/data}"
mkdir -p "$DATA_DIR"

if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "sqlite3 not found. Install SQLite CLI or rely on Spring Boot sql.init on first run."
  exit 1
fi

apply() {
  local db=$1
  local schema=$2
  local seed=$3
  echo "==> $db"
  sqlite3 "$db" < "$schema"
  if [[ -f "$seed" ]]; then
    sqlite3 "$db" < "$seed"
  fi
}

apply "$DATA_DIR/virbius-control.db" \
  "$ROOT/virbius-control/src/main/resources/db/schema.sql" \
  "$ROOT/virbius-control/src/main/resources/db/seed.sql"

apply "$DATA_DIR/virbius-engine.db" \
  "$ROOT/virbius-engine/src/main/resources/db/schema.sql" \
  "$ROOT/virbius-engine/src/main/resources/db/seed.sql"

apply "$DATA_DIR/virbius-compiler.db" \
  "$ROOT/virbius-compiler/src/main/resources/db/schema.sql" \
  "$ROOT/virbius-compiler/src/main/resources/db/seed.sql"

apply "$DATA_DIR/virbius-core.db" \
  "$ROOT/virbius-core/db/schema.sql" \
  /dev/null

apply "$DATA_DIR/virbius-gateway-agent.db" \
  "$ROOT/virbius-gateway-agent/db/schema.sql" \
  /dev/null

echo "Databases initialized under $DATA_DIR"
ls -la "$DATA_DIR"/*.db
