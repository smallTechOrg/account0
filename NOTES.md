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

- P9 (08:2x): first browser run of the real UI found no app bugs (my selectors only: "Review" matched the sidebar link, "Tax treatment" matched a flag). One real bug found by reading the UI while adding postings: the Ledger journal always linked `/review/<ref_id>` — wrong for bank/TDS/payroll journals; now links by ref type.
- P9: `next build` rewrites are fixed at build time, so the gates now build the UI into `.next-gate` with `ACCOUNT0_BACKEND_URL` = the gate backend; this also means a gate run never overwrites the `.next` of a running `./run.sh` UI. Real UI->API proxy is therefore tested end to end.
- P9: extraction is stubbed in the UI gate by env `ACCOUNT0_LLM_STUB_FIXTURE` (a `@Primary` transport returning a fixed JSON). Gate P9 spends no tokens; gates P1/P3/P7 still spend (~$0.15 total for 0-9).
- Cosmetic: macOS `sed -i` again needed `''`; python edit scripts used instead.

## HANDOFF (final, phases 0-9 built)
- Done and gated GREEN (see git log / PR for last run): P0-P9. Run `./scripts/gate-pN.sh` (SKIP_UNIT=1 skips tests/build).
- Run: `./run.sh` -> UI http://127.0.0.1:3710, API http://127.0.0.1:8710. Flyway migrations V1..V9. After pulling this phase RESTART the backend (new V9 migration) and rebuild the UI (`cd frontend && npx next build`).
- P9 added: PostingService journals (bank receipts/payments incl. customer net-of-TDS, TDS deduct/deposit, payroll), Playwright UI gate (`frontend/e2e/ui.mjs`), UI polish (Indian grouping, month/FY pickers, empty states, error toasts, STUB/ASSUMED labels), GSTR-1 CDNUR for export notes, cancelled-document tracking (Table 13), QRMP warning, old-regime flag in payroll UI.
- Known gaps / ideas for next session:
  1. Gmail/Drive transport never run against live Google; needs a real OAuth client to verify (see README).
  2. Tax assumptions are the `Assumed:` lines at the bottom of spec/capabilities.md (TDS rates/thresholds, new-regime slabs for later years, calendar dates, corpus paraphrases, P9 posting conventions). Have the CA review them.
  3. Sec 195 (non-resident TDS), old-regime salary, surcharge >50L, professional tax are deliberately not computed (flagged to ask CA). QRMP only warns.
  4. GSTR-1 JSON (incl. cdnur, doc_issue cancel) is best-effort portal shape - validate in the offline tool.
  5. Bank: unmatched/ignored lines are not posted; salary payments and TDS challans are not tied to bank lines (challans credit "Bank / cash (challan payments)"). Re-assigning a TDS section recreates the entry and clears its deposit details (pre-existing behaviour).
  6. UI e2e covers one happy path (profile -> upload -> review/confirm -> GST explain -> ledger, error toast, empty state); other screens are only smoke-tested for HTTP 200.
