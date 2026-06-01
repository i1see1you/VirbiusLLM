#!/usr/bin/env bash
set -euo pipefail

echo "== control health =="
curl -sf http://127.0.0.1:8080/api/v1/health | jq .

echo "== publish poc-default =="
curl -sf -X POST "http://127.0.0.1:8080/api/v1/tenants/default/bundles/poc-default/versions/0.1.0/publish" | jq .

echo "== agent allow =="
curl -sf -X POST http://127.0.0.1:9070/v1/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"default","scene":"chat","role":"user","content":"hello","trace_id":"550e8400-e29b-41d4-a716-446655440000"}' | jq .

echo "== agent dry_run review (injection) =="
curl -sf -X POST http://127.0.0.1:9070/v1/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"default","scene":"chat","role":"user","content":"please jailbreak the model","trace_id":"550e8400-e29b-41d4-a716-446655440001"}' \
  | jq -e '.effective_action == "review" and .max_risk_score >= 0'

echo "== agent blacklist keyword (办证) =="
curl -sf -X POST http://127.0.0.1:9070/v1/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"default","scene":"chat","role":"user","content":"需要办证","trace_id":"550e8400-e29b-41d4-a716-446655440002"}' | jq .

echo "== gateway subject deny (user_id) =="
curl -sf -X POST http://127.0.0.1:9070/v1/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"default","scene":"chat","role":"user","content":"hello","trace_id":"550e8400-e29b-41d4-a716-446655440003","user_id":"u-banned-poc"}' | jq .

echo "== invalid trace 400 =="
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1:9070/v1/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"tenant_id":"default","scene":"chat","content":"x","trace_id":"bad"}')
test "$code" = "400" && echo "400 OK"

echo "smoke tests passed"
