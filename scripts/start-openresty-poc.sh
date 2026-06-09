#!/usr/bin/env bash
# Start OpenResty/nginx for Virbius PoC (listen :9080 → Ollama :11434).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGING="${STAGING:-$ROOT/staging/default/0.1.0}"
PREFIX="${OPENRESTY_PREFIX:-$ROOT/.openresty-poc}"
PORT="${VIRBIUS_OPENRESTY_PORT:-9080}"

# Default prefix after: ./configure && make && sudo make install
DEFAULT_OPENRESTY_HOME="/usr/local/openresty"
OPENRESTY_HOME="${OPENRESTY_HOME:-}"

if [[ -z "$OPENRESTY_HOME" && -d "$DEFAULT_OPENRESTY_HOME" ]]; then
  OPENRESTY_HOME="$DEFAULT_OPENRESTY_HOME"
fi

resolve_openresty_bin() {
  if [[ -n "${OPENRESTY_NGINX_BIN:-}" && -x "$OPENRESTY_NGINX_BIN" ]]; then
    echo "$OPENRESTY_NGINX_BIN"
    return 0
  fi
  if [[ -n "$OPENRESTY_HOME" ]]; then
    if [[ -x "$OPENRESTY_HOME/bin/openresty" ]]; then
      echo "$OPENRESTY_HOME/bin/openresty"
      return 0
    fi
    if [[ -x "$OPENRESTY_HOME/nginx/sbin/nginx" ]]; then
      echo "$OPENRESTY_HOME/nginx/sbin/nginx"
      return 0
    fi
  fi
  local candidate
  for candidate in \
    /usr/local/openresty/bin/openresty \
    /usr/local/openresty/nginx/sbin/nginx \
    /opt/homebrew/opt/openresty/bin/openresty \
    /opt/homebrew/opt/openresty/nginx/sbin/nginx \
    "$HOME/openresty/bin/openresty" \
    "$HOME/openresty/nginx/sbin/nginx" \
    "$HOME/Documents/openresty-master/openresty-1.31.1.1/install/bin/openresty" \
    "$HOME/Documents/openresty-master/openresty-1.31.1.1/install/nginx/sbin/nginx"; do
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  if command -v openresty >/dev/null 2>&1; then
    command -v openresty
    return 0
  fi
  for candidate in "$HOME/Documents/openresty-master"/openresty-*/install/bin/openresty \
    "$HOME/Documents/openresty-master"/openresty-*/install/nginx/sbin/nginx \
    "$HOME/Documents/openresty-master"/openresty-*/nginx/sbin/nginx; do
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

hint_unbuilt_source() {
  local home="${1:-}"
  if [[ -z "$home" && -d "$HOME/Documents/openresty-master/openresty-1.31.1.1" ]]; then
    home="$HOME/Documents/openresty-master/openresty-1.31.1.1"
  fi
  if [[ -n "$home" && -f "$home/configure" && ! -x "$home/install/nginx/sbin/nginx" ]]; then
    echo ""
    echo "Detected OpenResty SOURCE at: $home (not installed to prefix yet)"
    echo "  cd \"$home\" && ./configure --prefix=\"\$PWD/install\" && make && make install"
    echo "  Or default system prefix: ./configure && make && sudo make install  → /usr/local/openresty/"
    return 0
  fi
  return 1
}

NGINX="$(resolve_openresty_bin)" || {
  echo "ERROR: OpenResty/nginx executable not found."
  hint_unbuilt_source "${OPENRESTY_HOME:-}" || true
  echo "  Expected after install: /usr/local/openresty/bin/openresty"
  echo "  Or: export OPENRESTY_HOME=/usr/local/openresty"
  exit 1
}

if [[ -z "${OPENRESTY_HOME:-}" ]]; then
  case "$NGINX" in
    */bin/openresty) OPENRESTY_HOME="${NGINX%/bin/openresty}" ;;
    */nginx/sbin/nginx) OPENRESTY_HOME="${NGINX%/nginx/sbin/nginx}" ;;
  esac
  export OPENRESTY_HOME
fi

if [[ ! -f "$STAGING/gateway/openresty/locations.conf" ]]; then
  echo "Missing OpenResty artifacts; running compile-openresty-poc.sh ..."
  "$ROOT/scripts/compile-openresty-poc.sh"
fi

mkdir -p "$PREFIX/logs" "$PREFIX/conf"

CONF="$PREFIX/conf/nginx.conf"
sed \
  -e "s|__VIRBIUS_REPO__|$ROOT|g" \
  -e "s|__STAGING__|$STAGING|g" \
  "$ROOT/examples/gateway/openresty-poc/0.1.0/nginx.conf.template" >"$CONF"

MIME=""
for candidate in \
  "${OPENRESTY_HOME:+$OPENRESTY_HOME/nginx/conf/mime.types}" \
  /usr/local/openresty/nginx/conf/mime.types \
  "$(dirname "$NGINX")/../nginx/conf/mime.types" \
  "$(dirname "$NGINX")/../conf/mime.types" \
  /opt/homebrew/etc/openresty/mime.types; do
  if [[ -n "$candidate" && -f "$candidate" ]]; then
    MIME="$candidate"
    break
  fi
done
if [[ -n "$MIME" ]]; then
  sed -i '' "s|include       mime.types;|include       $MIME;|" "$CONF" 2>/dev/null || \
    sed -i "s|include       mime.types;|include       $MIME;|" "$CONF"
fi

if lsof -ti :"$PORT" >/dev/null 2>&1; then
  echo "Port $PORT in use; stopping existing listener ..."
  lsof -ti :"$PORT" | xargs kill -9 2>/dev/null || true
  sleep 1
fi

echo "Starting OpenResty on :$PORT"
echo "  binary:  $NGINX"
echo "  openresty home: $OPENRESTY_HOME"
echo "  virbius prefix: $PREFIX"
echo "  staging: $STAGING"
"$NGINX" -p "$PREFIX" -c "$CONF" -t
"$NGINX" -p "$PREFIX" -c "$CONF"

echo ""
echo "Virbius OpenResty ready: http://127.0.0.1:$PORT/v1/chat/completions"
echo "Upstream Ollama: see $STAGING/gateway/openresty/upstreams.conf"
echo "Stop: $NGINX -p $PREFIX -c $CONF -s stop"
