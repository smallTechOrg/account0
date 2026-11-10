#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
[ "${SKIP_UNIT:-}" = "1" ] || run_unit_tests
# STUB extraction: backend returns fixtures/stub/extraction-nimbus.json for every LLM call (no tokens spent in this gate).
export ACCOUNT0_LLM_STUB_FIXTURE="$ROOT/fixtures/stub/extraction-nimbus.json"
export ACCOUNT0_ANTHROPIC_API_KEY="stub-not-a-real-key"
boot_backend
python3 "$ROOT/scripts/gate_p9.py" "$BASE" || { tail -30 "$GATE_DATA/app.log"; fail "P9 ledger flow"; }
(cd "$ROOT/frontend" && npx next build >"$GATE_DATA/next-build.log" 2>&1) || { tail -30 "$GATE_DATA/next-build.log"; fail "frontend build for gate"; }
(cd "$ROOT/frontend" && npx next start -p 3799 -H 127.0.0.1 > "$GATE_DATA/next.log" 2>&1 &) ; sleep 4
(cd "$ROOT/frontend" && node e2e/ui.mjs http://127.0.0.1:3799 "$BASE" "$ROOT") || { pkill -f "next start -p 3799" || true; fail "browser UI flow"; }
for p in / /inbox /gst /ledger /bank /tds /payroll /calendar /learn; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:3799$p"); [ "$code" = 200 ] || { pkill -f "next start -p 3799" || true; fail "UI $p -> $code"; }; pass "UI $p 200"
done
pkill -f "next start -p 3799" || true
echo "GATE P9 GREEN"
