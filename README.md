# Account0

Local-first AI back-office and compliance co-pilot for a small Indian Private Limited company
(GST monthly filer, LUT exports, RCM imports of services). Organise books, independently recompute
what your CA files, and learn the process.

## Prerequisites
- JDK 25 (`java -version`); no Maven/Gradle install needed (wrapper committed).
- Node 22.16.0 via asdf (`.tool-versions`): `asdf install nodejs 22.16.0`.
- `.env` in repo root (see `.env.example`): `ACCOUNT0_ANTHROPIC_API_KEY`, `ACCOUNT0_ANTHROPIC_WORKSPACE_ID`.

## Run
```bash
./run.sh            # backend http://127.0.0.1:8710 + UI http://127.0.0.1:3710
./run.sh backend    # backend only
```
Data lives in `data/` (SQLite `data/account0.db`, documents, watched inbox `data/inbox/`).

## Gates (per phase)
```bash
./scripts/gate-p0.sh   # tests, build, fresh-DB boot, /health, real LLM key check
./scripts/gate-p1.sh   # + real extraction of fixture invoices, review/confirm, ledger
./scripts/gate-p2.sh   # + GSTR-3B/GSTR-1 recompute, exports, CA pack
./scripts/gate-p3.sh   # + CA scrutiny (real PDF transcription), 2B ITC match, alerts
./scripts/gate-p4.sh   # + TDS register/26Q, payroll Sec 192/24Q
./scripts/gate-p5.sh   # + bank statement import & reconciliation
```
Backend tests only: `cd backend && ./gradlew test`. Frontend: `cd frontend && npm test && npx next build`.

## Docs
`spec/capabilities.md`, `spec/architecture.md`, `spec/agent.md`, `spec/roadmap.md`, `NOTES.md` (build journal).
