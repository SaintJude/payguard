#!/usr/bin/env bash
# Phase 1-2 verification scenarios, per docs/phase-notes/PHASE_1_TASKS.md.
# Run via `make verify` (which starts the services first and stops them after).
set -euo pipefail

API="http://localhost:8080"

fail() {
  echo "FAIL: $1"
  exit 1
}

echo "== Scenario 1: happy path (submit, poll until completed) =="
KEY="verify-happy-$(date +%s)"
HTTP_CODE=$(curl -s -o /tmp/payguard-verify-resp.json -w '%{http_code}' \
  -X POST "$API/payments" \
  -H 'Content-Type: application/json' \
  -d "{\"amount\":10.00,\"idempotencyKey\":\"$KEY\"}")
[ "$HTTP_CODE" = "202" ] || fail "expected 202 Accepted, got $HTTP_CODE"
ID=$(jq -r .id /tmp/payguard-verify-resp.json)
echo "created payment $ID"

STATUS="PENDING"
for i in $(seq 1 10); do
  STATUS=$(curl -s "$API/payments/$ID" | jq -r .status)
  echo "poll $i: $STATUS"
  [ "$STATUS" = "COMPLETED" ] && break
  sleep 1
done
[ "$STATUS" = "COMPLETED" ] || fail "payment $ID never reached COMPLETED (last status: $STATUS)"
echo "PASS: happy path"

echo "== Scenario 2: idempotency (same key twice -> one record) =="
KEY2="verify-idem-$(date +%s)"
BODY2="{\"amount\":5.00,\"idempotencyKey\":\"$KEY2\"}"
ID_A=$(curl -s -X POST "$API/payments" -H 'Content-Type: application/json' -d "$BODY2" | jq -r .id)
ID_B=$(curl -s -X POST "$API/payments" -H 'Content-Type: application/json' -d "$BODY2" | jq -r .id)
[ "$ID_A" = "$ID_B" ] || fail "idempotency violated: $ID_A != $ID_B"
echo "PASS: idempotency ($ID_A returned both times)"

echo "== Scenario 3: retry-then-complete =="
echo "The worker hardcodes a failure on a payment's first processing attempt"
echo "(see docs/architecture/PHASE_1_DESIGN.md). Scenario 1 above only reaches"
echo "COMPLETED if that first failure was retried and succeeded, so it already"
echo "proves this. Explicitly re-confirming here for a fresh payment:"
KEY3="verify-retry-$(date +%s)"
ID_C=$(curl -s -X POST "$API/payments" -H 'Content-Type: application/json' \
  -d "{\"amount\":7.50,\"idempotencyKey\":\"$KEY3\"}" | jq -r .id)
STATUS3="PENDING"
for i in $(seq 1 10); do
  STATUS3=$(curl -s "$API/payments/$ID_C" | jq -r .status)
  [ "$STATUS3" = "COMPLETED" ] && break
  sleep 1
done
[ "$STATUS3" = "COMPLETED" ] || fail "payment $ID_C did not recover via retry (last status: $STATUS3)"
echo "PASS: retry-then-complete"

echo "ALL SCENARIOS PASSED"
