# Roadmap

All gates run from repo root. Backend tests use a fake LLM transport; `scripts/gate-*.sh` boots the real
app on a fresh temp DB (fresh migration check), curls endpoints and asserts content. Gates that call Claude
need `.env`.

## P0 — Scaffold
- Goal: app boots; DB migrates; UI shows health; LLM key + workspace header verified.
- Slices: Gradle wrapper + Spring Boot; Flyway V1 (tenant, setting, llm_call, audit_log); `/health`;
  LlmGateway + usage ledger + spend cap; `/api/llm/ping`, `/api/llm/usage`; Next.js dashboard; run.sh.
- Key files: `backend/build.gradle`, `common/SystemController.java`, `llm/*`, `frontend/app/page.tsx`, `run.sh`.
- Gate: `scripts/gate-p0.sh` (= `./gradlew test` + `npm test` + `npm run build` + boot on fresh DB + curl `/health` UP + real ping OK).
- User test: `./run.sh`, open http://127.0.0.1:3710, click "Test LLM key".

## P1 — Company profile, inbox, extraction, review queue, ledger
- Slices: company profile + GSTIN validation; document upload/batches/progress; watched folder; Google stubs;
  extraction service (one Claude call/doc) + deterministic checks; review screen (doc side-by-side); confirm
  → invoice CONFIRMED + ledger journal + optional counterparty rule; duplicate detection; audit; ledger + TB + CSV.
- Key files: `company/*`, `documents/*`, `extraction/*`, `invoice/*`, `ledger/*`, V2 migration, `frontend/app/{company,inbox,review,invoices,ledger,rules}`.
- Gate: `scripts/gate-p1.sh` (tests, build, boot fresh DB, set profile, upload 3 fixture PDFs as a batch,
  wait for batch, assert extracted invoice numbers/GSTINs match fixtures, confirm all, assert trial balance balances).
- User test: Company profile → save; Inbox → drop `fixtures/invoices/*.pdf`; Review queue → check & confirm; Ledger.

## P2 — GST recompute + explain + exports + CA pack
- Slices: Gstr3bCalculator (3.1, 3.2, 4, 6.1 set-off), Gstr1Calculator (B2B, B2CL, B2CS, EXP, CDNR, HSN, DOC);
  explain endpoint (rule + ref + source invoices) and clickable UI; GSTR-1 JSON; Excel; CA pack zip; filing
  history + period lock.
- Gate: `scripts/gate-p2.sh` (unit tests with hand-computed expectations; boot; seed confirmed invoices via API; assert 3B figures; download JSON/xlsx/zip and check entries).
- User test: GST page → pick month → click any number.

## P3 — CA scrutiny + 2B ITC match + alerts
- Slices: upload CA's filed 3B JSON (portal format) or typed figures (PDF via Claude parse → user confirms);
  diff table; questions generator (deterministic templates, optional LLM polish); 2B JSON import; ITC match
  (exact/fuzzy on GSTIN+inv no+amount); alerts (not in 2B, duplicate, RCM unpaid).
- Gate: `scripts/gate-p3.sh`.

## P4 — TDS + payroll
- Slices: TDS sections table (194C 1%/2%, 194J 2%/10%, 194I, 194H, 194Q) with thresholds; user-confirmed section
  per vendor rule; TDS register; 192 salary from payroll CSV (new regime slab calc); 26Q/24Q CSV data; threshold alerts.
- Gate: `scripts/gate-p4.sh`.

## P5 — Bank reconciliation
- CSV/XLS statement import (column mapping), match against invoices (amount ± tolerance, date window, name), manual match.

## P6 — Compliance calendar + month-close checklist
- Generated due dates per FY (GSTR-1 11th, 3B 20th, TDS 7th, 24Q/26Q quarterly, PF/ESI 15th, AOC-4, MGT-7A, DIR-3 KYC, ADT-1, advance tax, ITR-6) with status; checklist per month.

## P7 — Learning layer
- Walkthroughs on own data, "why" notes, Q&A with citations over local corpus, DIY mode.

## P8 — Gmail/Drive integration
- OAuth flow + Gmail label/Drive folder pollers behind interfaces; mocked in tests; disconnected until user adds credentials.
