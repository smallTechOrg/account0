#!/usr/bin/env bash
# Starts Account0: backend (Spring Boot, http://127.0.0.1:8710) + frontend (Next.js, http://127.0.0.1:3710).
# Usage: ./run.sh            (dev mode)
#        ./run.sh backend    (backend only)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
if [ -f .env ]; then set -a; source ./.env; set +a; fi
mkdir -p data/inbox data/documents

pids=()
cleanup() { for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM

(cd backend && ./gradlew -q bootJar && exec java --enable-native-access=ALL-UNNAMED -jar build/libs/account0-0.1.0.jar) &
pids+=($!)

if [ "${1:-}" != "backend" ]; then
  (cd frontend && { [ -d node_modules ] || npm install --no-audit --no-fund; } && exec npx next dev -p 3710 -H 127.0.0.1) &
  pids+=($!)
  echo "Account0: UI http://127.0.0.1:3710  API http://127.0.0.1:8710  (Ctrl-C to stop)"
fi
wait
