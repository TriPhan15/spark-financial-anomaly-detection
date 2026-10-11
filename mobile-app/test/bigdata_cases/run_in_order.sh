#!/usr/bin/env bash
# Runs the Big Data cases from the worst situation to the best, and stops at the first level that fails: a level only
# counts when every level below it is green.
#
#   L0  the Big Data API is up with both models loaded, and its own end-to-end check passes (scripts/e2e_check.py)
#   L1  failure modes (service down, slow, wrong, too little history)       -> app must fail open
#   L2  data contract (payload, history cut, ordering, limits)
#   L3  the real model's judgement, including the habit rule for absurd amounts
#   L4  screens: manual entry, edit, receipt scan with the pencil           (flagged expenses)
#   L5  the normal path                                                    (runs inside l4_screen_flows_test.dart)
#
# Needs: docker compose -f BigData/BigData/docker-compose.bigdata.yml --profile api up -d --build fraud-api
# Run from mobile-app/:  bash test/bigdata_cases/run_in_order.sh
set -u
API="${FRAUD_API_URL:-http://127.0.0.1:8001}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

step() { printf '\n=== %s\n' "$1"; }
fail() { printf '\nSTOP: %s failed, the levels above it are not run.\n' "$1"; exit 1; }

step "L0 API health and its end-to-end check"
curl -sf "$API/health" | grep -q '"behaviorModelLoaded":true' || fail "L0 (API not up or model not loaded at $API)"
python -X utf8 "$ROOT/BigData/BigData/scripts/e2e_check.py" --base-url "$API" | tail -3 | grep -q "passed" || fail "L0 e2e_check"

for level in l1_failure_modes l2_data_contract l3_model_behavior l4_screen_flows; do
  step "$level"
  flutter test "test/bigdata_cases/${level}_test.dart" --reporter expanded 2>&1 | grep -E "^[0-9:]+ \+[0-9]+(-| -)?[0-9]*: (L[0-9]|All tests|Some tests)|\[E\]" | tail -40
  [ "${PIPESTATUS[0]}" -eq 0 ] || fail "$level"
done
printf '\nAll levels passed.\n'
