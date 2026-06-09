#!/usr/bin/env bash
# One-time build OpenResty from source tree (produces nginx/sbin/nginx).
set -euo pipefail

SRC="${OPENRESTY_SRC:-$HOME/Documents/openresty-master/openresty-1.31.1.1}"
PREFIX="${OPENRESTY_INSTALL_PREFIX:-$SRC/install}"
JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

if [[ ! -f "$SRC/configure" ]]; then
  echo "ERROR: OpenResty source not found: $SRC/configure"
  echo "  Set OPENRESTY_SRC to your openresty-*/ directory."
  exit 1
fi

echo "Building OpenResty"
echo "  source: $SRC"
echo "  prefix: $PREFIX"
echo ""

cd "$SRC"
./configure --prefix="$PREFIX"
make -j"$JOBS"

NGINX="$PREFIX/nginx/sbin/nginx"
if [[ ! -x "$NGINX" ]]; then
  echo "ERROR: build finished but nginx missing: $NGINX"
  exit 1
fi

echo ""
echo "Build OK: $NGINX"
echo ""
echo "Add to your shell (or copy scripts/openresty-env.local.example → openresty-env.local.sh):"
echo "  export OPENRESTY_HOME=\"$PREFIX\""
echo "  export OPENRESTY_NGINX_BIN=\"$NGINX\""
echo ""
echo "Then:"
echo "  cd $(cd "$(dirname "$0")/.." && pwd)"
echo "  ./scripts/start-openresty-poc.sh"
