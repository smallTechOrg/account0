#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
run_unit_tests
boot_backend
H=$(curl -sf "$BASE/health"); echo "$H"
assert_contains "$H" '"status":"UP"'
assert_contains "$H" '"llmKeyConfigured":true'
assert_contains "$H" '"llmWorkspaceConfigured":true'
P=$(curl -sf -X POST "$BASE/api/llm/ping"); echo "$P"
assert_contains "$P" '"ok":true'
U=$(curl -sf "$BASE/api/llm/usage")
assert_contains "$U" '"purpose":"key-check"'
echo "GATE P0 GREEN"
