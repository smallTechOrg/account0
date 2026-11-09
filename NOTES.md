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

## HANDOFF (04:35, model switch)
- Done & gated: P0, P1, P2 (commits phase-0/1/2; gates gate-p0/p1/p2 all GREEN — P1 incl. real extraction of 4 fixtures, ~$0.08).
- Half-done P3 (wip commit, NOT compiling yet, no gate):
  - `V3__scrutiny_itc.sql` (filed_return, gstr2b_import, gstr2b_doc) written.
  - `scrutiny/` Gstr3bFigures, ScrutinyEngine, ScrutinyService, ScrutinyController written.
  - COMPILE BREAK: ScrutinyService.importPdf calls `docs.ingestReference(bytes, filename)` which does not exist in DocumentService — add it (store file with source='REFERENCE', status='REFERENCE', no extraction event).
- Next exact steps: add ingestReference; `itc/` 2B JSON import + matcher (ctin + normalised inv no, amount tol ₹1; statuses MATCHED/AMOUNT_MISMATCH/IN_2B_NOT_IN_BOOKS/IN_BOOKS_NOT_IN_2B); `alerts/` live alerts endpoint; frontend /scrutiny, /itc pages + dashboard alerts + nav links; JUnit for ScrutinyEngine; scripts/gate-p3.sh; commit `phase-3: ...`; update PR #1 body.
