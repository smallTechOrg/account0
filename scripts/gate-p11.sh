#!/usr/bin/env bash
# P11: ITC claimed once, 2B accept/reject, Table 11 advances, Rules 42/43/37, PF/ESI + bank postings, 194I rent period (no LLM spend).
source "$(dirname "$0")/lib.sh"
[ "${SKIP_UNIT:-}" = "1" ] || run_unit_tests
export ACCOUNT0_ANTHROPIC_API_KEY="${ACCOUNT0_ANTHROPIC_API_KEY:-stub-not-a-real-key}"
boot_backend
python3 "$ROOT/scripts/gate_p11.py" "$BASE" || { tail -30 "$GATE_DATA/app.log"; fail "P11 flow"; }
if [ -d "$ROOT/frontend/.next-gate" ]; then
  (cd "$ROOT/frontend" && npx next start -p 3799 -H 127.0.0.1 > "$GATE_DATA/next.log" 2>&1 &) ; sleep 4
  for p in /gst /itc /bank /payroll /tds /advances; do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:3799$p"); [ "$code" = 200 ] || { pkill -f "next start -p 3799" || true; fail "UI $p -> $code"; }; pass "UI $p 200"
  done
  pkill -f "next start -p 3799" || true
fi
echo "GATE P11 GREEN"
