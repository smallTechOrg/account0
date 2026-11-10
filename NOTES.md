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

- P10 (08:4x-09:3x): fixed the independent tax-correctness audit. WebSearch verified the UNCERTAIN items: Form 131 = old 16A (official FAQ), s.397(2) no-PAN rule (5% floor for 194Q kept, exact mapping marked "verify with CA"), DIR-3 KYC 3-year cycle by 30 June (first-cycle anchoring unclear in sources -> UI says verify with CA), Code on Social Security in force 21 Nov 2025 (due dates unverified -> citation only). s.393 payment codes come from secondary blogs -> labelled verify with CA. Item numbering (#27) left unchanged.
- P10: the RCM time-of-supply fix moves an unpaid RCM invoice to invoice+61 days; the fixture Atlas invoice is now seeded with `rcm_paid_on` = invoice date in the gates/tests (assertions unchanged). gate_p6 count 85 -> 94 (new calendar items + one 15CA event).
- P10: all gates p0-p10 green on the first full run after the changes; P1 extraction cost $0.079.

## HANDOFF (final, phases 0-10 built)
- Done and gated GREEN (last full run 2026-11-10, p0 with unit tests + UI build, p1-p10 with SKIP_UNIT=1): P0-P10. Run `./scripts/gate-pN.sh` (SKIP_UNIT=1 skips tests/build).
- Run: `./run.sh` -> UI http://127.0.0.1:3710, API http://127.0.0.1:8710. Flyway migrations V1..V10. After pulling this phase RESTART the backend (new V10 migration: invoice.rcm_paid_on / nil_type / buyer_kind) and rebuild the UI (`cd frontend && npx next build`).
- P10 (tax audit) changed numbers: 3B set-off now minimises cash; 4A(5) comes from matched GSTR-2B lines when 2B is imported (else PROVISIONAL); RCM tax goes to the month of its time of supply (enter the payment date on RCM invoices!); 194I is per month (₹50k); 194J_DIR new; no-PAN 5%/20%; DIR-3 KYC needs directors recorded (Calendar > Directors); GSTR-1 JSON is refused while an HSN/SAC is missing. Details: the "Assumed (P10)" lines in spec/capabilities.md; regression tests in P10AuditFixesTest.
- Known gaps / ideas for next session:
  1. Gmail/Drive transport never run against live Google; needs a real OAuth client to verify (see README).
  2. Tax assumptions are the `Assumed:` lines in spec/capabilities.md - have the CA review them (esp. P10: s.393 codes, DIR-3 first cycle, no-PAN mapping, RCM +61 days).
  3. Not computed (notes only): Rules 42/43 common-credit reversal, Rule 37 180-day reversal (note when bank data exists), GSTR-1 Table 11 advances, Sec 195 TDS, old-regime salary, surcharge >50L, professional tax. QRMP only warns.
  4. 2B: POSSIBLE_MATCH / AMOUNT_MISMATCH lines cannot be "accepted" by the user yet (they are left out of 4A(5)); an invoice claimed provisionally (no 2B) and later matched in another month's 2B can be double counted.
  5. Ledger (#22 rest): no PF/ESI postings, salary payable not cleared by bank, no forex gain/loss or bank charges, challan suspense not reconciled with bank lines.
  6. GSTR-1 JSON is best-effort portal shape - validate in the offline tool. UI e2e covers one happy path; P10 UI changes are only smoke-tested (HTTP 200).
