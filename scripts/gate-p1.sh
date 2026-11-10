#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
[ "${SKIP_UNIT:-}" = "1" ] || run_unit_tests
boot_backend
python3 "$ROOT/scripts/gate_p1.py" "$BASE" || { tail -30 "$GATE_DATA/app.log"; fail "P1 flow"; }
echo "GATE P1 GREEN"
