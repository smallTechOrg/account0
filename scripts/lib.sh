# Shared helpers for gate scripts. Boots the real backend jar on a FRESH temp data dir (fresh migration check).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE_PORT="${GATE_PORT:-8799}"
BASE="http://127.0.0.1:$GATE_PORT"
if [ -f "$ROOT/.env" ]; then set -a; source "$ROOT/.env"; set +a; fi

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
assert_contains() { echo "$1" | grep -q -- "$2" || fail "expected '$2' in: ${1:0:600}"; pass "contains $2"; }
jqr() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

run_unit_tests() {
  (cd "$ROOT/backend" && ./gradlew test -q) || fail "backend tests"; pass "backend tests"
  (cd "$ROOT/frontend" && npm test --silent >/dev/null) || fail "frontend tests"; pass "frontend tests"
  (cd "$ROOT/frontend" && npx next build >/tmp/account0-next-build.log 2>&1) || { tail -30 /tmp/account0-next-build.log; fail "frontend build"; }; pass "frontend build"
}

boot_backend() {
  (cd "$ROOT/backend" && ./gradlew -q bootJar) || fail "bootJar"
  GATE_DATA="$(mktemp -d)"
  ACCOUNT0_PORT=$GATE_PORT ACCOUNT0_DATA_DIR="$GATE_DATA" ACCOUNT0_INBOX_DIR="$GATE_DATA/inbox" \
    java --enable-native-access=ALL-UNNAMED -jar "$ROOT/backend/build/libs/account0-0.1.0.jar" > "$GATE_DATA/app.log" 2>&1 &
  APP_PID=$!
  trap 'kill $APP_PID 2>/dev/null || true' EXIT
  for i in $(seq 1 60); do curl -sf "$BASE/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -sf "$BASE/health" >/dev/null || { tail -40 "$GATE_DATA/app.log"; fail "backend did not boot"; }
  pass "backend booted on fresh DB at $GATE_DATA"
}
