#!/usr/bin/env bash
# Compile poc-default bundle for OpenResty (path A: compile-time flatten).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BUNDLE="${BUNDLE:-$ROOT/examples/poc-default-bundle.yaml}"
OUT="${OUT:-$ROOT/staging/default/0.1.0}"
# Plan A: effective JSON lists_file / scene_registry_file point at control artifacts (data/gateway/).
VIRBIUS_DATA_DIR="${VIRBIUS_DATA_DIR:-$ROOT/data}"
DEPLOY_PREFIX="${DEPLOY_PREFIX:-$VIRBIUS_DATA_DIR}"
ACCESS_LUA="${ACCESS_LUA:-$ROOT/virbius-gateway/plugins/openresty/access.lua}"

MVN="${MVN:-$HOME/.local/apache-maven-3.9.6/bin/mvn}"
JAR="$ROOT/virbius-compiler/target/virbius-compiler-0.1.0-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
  echo "Building virbius-compiler..."
  "$MVN" -q -pl virbius-compiler -am package -DskipTests
fi

export VIRBIUS_OPENRESTY_ACCESS_LUA="$ACCESS_LUA"

java -jar "$JAR" \
  -i "$BUNDLE" \
  -o "$OUT" \
  --target=gateway \
  --gateway=openresty \
  --deploy-prefix="$DEPLOY_PREFIX" \
  --deploy-layout=control-data

echo ""
echo "OpenResty artifacts:"
echo "  $OUT/gateway/openresty/manifest.json"
echo "  $OUT/gateway/openresty/locations.conf"
echo "  $OUT/gateway/openresty/upstreams.conf"
echo "  $OUT/gateway/openresty/effective-*.json"
echo ""
echo "Gateway data paths (control ArtifactService; deploy-prefix=$DEPLOY_PREFIX):"
echo "  $DEPLOY_PREFIX/gateway/default-access-lists.json"
echo "  $DEPLOY_PREFIX/gateway/default-scene-registry.json"
echo "  Run ./scripts/run-local.sh first so control refreshArtifacts writes the above."
echo ""
echo "Set VIRBIUS_OPENRESTY_ACCESS_LUA=$ACCESS_LUA when recompiling if access.lua moves."
