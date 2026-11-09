# NOTES — harness-friction journal
Run start: 2026-11-10 03:47 (autonomous overnight build, branch feature/account0-20261110-0347-v0.1)

- 03:50 `gradle wrapper` failed URL validation of services.gradle.org (307 redirect); used `--no-validate-url` with the cached 9.8.1 distribution. Wrapper works.
- 03:52 A Bash heredoc containing regex (`\B(?=...)`) tripped the harness rm-safety parser; switched to the Write tool for TS files.
- 03:55 Flyway (Boot 3.5.7 managed) handles SQLite with flyway-core alone — no extra module needed.
- 03:58 P0 gate green incl. real Haiku 5.5 ping with workspace header.
- 04:10 P1: first real extraction run on 4 generated fixtures (B2B sale, LUT export in USD with INR equivalent, inter-state purchase, foreign SaaS vendor) — all fields matched expected on first attempt; ~$0.02/document with Sonnet 5.5, ~7 s/document.
- 04:10 Decision: RCM invoices store OUR tax liability in the tax columns and keep total = amount payable to vendor; checks + ledger handle this explicitly.
- 04:25 P2: 3B set-off implemented per Sec 49/49A + Rule 88A (IGST credit first → IGST, CGST, SGST). Assumed: 17(5)-blocked ITC is reported in 4A then reversed in 4B(1) per Circular 170/02/2022. GSTR-1 JSON shape is best-effort (offline tool); flagged in UI.
- 04:25 Next.js rewrites are resolved at build time, so the gate's UI smoke test only checks pages render (200), not proxying to the gate's alternate backend port.

- 04:15 P3 compile fix: added DocumentService.ingestReference (source/status REFERENCE, never queued for extraction). Gate P3 green first try incl. real Claude transcription of a generated filed-3B PDF.
- 04:35 P4: first payroll test caught my own off-by-one (months remaining from April is 12, not 11) - good reason to keep hand-computed expectations in tests.
- 04:50 macOS `sed -i`/`;`-chained substitutions repeatedly failed ("unescaped newline"); used small python3 edit scripts instead.
- 05:05 P7 retrieval: plural stemming made "good" match "goods" (194Q) so an off-topic question reached the LLM; fixed with a query-coverage rule (>=2 of >=3 typed words must hit). Off-topic now declines with zero LLM spend (asserted in gate-p7).
- 05:20 P8: no Google credentials exist, so the Gmail/Drive HTTP transport is UNVERIFIED against live Google; OAuth flow, token encryption, dedupe, refresh and disconnect are tested against an in-memory fake.
- Gate cost for a full re-run of gates 0-8 is roughly $0.15 (P1 extraction of 4 invoices dominates; P3 one PDF transcription; P7 two Q&A calls).

## HANDOFF (final, all phases built)
- Done and gated GREEN (last full run, fresh DB each): P0, P1, P2, P3, P4, P5, P6, P7, P8. Run `./scripts/gate-pN.sh` (SKIP_UNIT=1 skips tests/build).
- Run: `./run.sh` -> UI http://127.0.0.1:3710, API http://127.0.0.1:8710. Flyway migrations V1..V8.
- Nothing half-done. Known gaps / ideas for next session:
  1. No journal posting for bank payments, TDS payable, or salary (ledger only has invoice postings).
  2. Gmail/Drive transport never run against live Google; needs a real OAuth client to verify (see README).
  3. Tax assumptions are listed as `Assumed:` lines at the bottom of spec/capabilities.md (TDS rates/thresholds, new-regime slabs for later years, calendar dates, corpus paraphrases). Have the CA review them.
  4. Sec 195 (non-resident TDS), old-regime salary, surcharge >50L, professional tax, QRMP filers are deliberately not computed (flagged to ask CA).
  5. GSTR-1 JSON is best-effort portal shape - validate in the offline tool (from P2).
  6. Frontend has no automated browser tests; gates only check pages render (200) and the API.
