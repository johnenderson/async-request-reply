#!/usr/bin/env bash
set -e

BASE="http://localhost:8080"
PAYLOAD='{"type":"relatorio","data":{"mes":"junho","ano":2026}}'

echo "=== 1. POST /jobs ==="
RESPONSE=$(curl -s -i -X POST "$BASE/jobs" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD")
echo "$RESPONSE"

JOB_ID=$(echo "$RESPONSE" | grep -o '"jobId":"[^"]*"' | cut -d'"' -f4)
echo ""
echo "=== Job ID: $JOB_ID ==="

echo ""
echo "=== 2. GET status (imediato — esperado PROCESSING) ==="
curl -s "$BASE/jobs/$JOB_ID/status" | python3 -m json.tool

echo ""
echo "=== Aguardando 6s para o job completar... ==="
sleep 6

echo ""
echo "=== 3. GET status (após 6s — esperado COMPLETED) ==="
curl -s "$BASE/jobs/$JOB_ID/status" | python3 -m json.tool

echo ""
echo "=== 4. GET status de job inexistente (esperado 404) ==="
curl -s -o /dev/null -w "HTTP Status: %{http_code}\n" "$BASE/jobs/nao-existe/status"
