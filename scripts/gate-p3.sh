#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
[ "${SKIP_UNIT:-}" = "1" ] || run_unit_tests
boot_backend
python3 "$ROOT/scripts/gate_p3.py" "$BASE" || { tail -30 "$GATE_DATA/app.log"; fail "P3 flow"; }
(cd "$ROOT/frontend" && npx next start -p 3799 -H 127.0.0.1 > "$GATE_DATA/next.log" 2>&1 &) ; sleep 4
for p in / /scrutiny /itc /gst; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:3799$p"); [ "$code" = 200 ] || fail "UI $p -> $code"; pass "UI $p 200"
done
pkill -f "next start -p 3799" || true
echo "GATE P3 GREEN"
