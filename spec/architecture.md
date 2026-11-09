# Architecture

## Stack
| Layer | Choice | Rationale |
|---|---|---|
| Backend | Java 25 runtime (bytecode target 21), Spring Boot 3.5.7, Gradle 9.8.1 wrapper (committed) | User preference (Java). No global Maven/Gradle on the machine → wrapper. Boot 3.5 is the latest 3.x line. |
| Persistence | SQLite (xerial sqlite-jdbc) + Flyway migrations, Spring `JdbcTemplate` | Local-first single file DB (`data/account0.db`). JdbcTemplate over JPA: explicit SQL, no dialect issues with SQLite, easy audit. Pool size 1 + WAL + busy_timeout avoids SQLITE_BUSY. |
| Frontend | Next.js 15 (App Router, client components), React 19, TypeScript, plain CSS | User preference. Node 22.16.0 pinned via `.tool-versions` (asdf). `/api/*` and `/health` proxied to backend via `next.config.mjs` rewrites — one origin, no CORS. |
| LLM | Anthropic Messages API over raw HTTP (`java.net.http`) | User asked for HTTP. Header `anthropic-workspace-id` is sent on every request (required by this key). |
| Documents | Original files stored under `data/documents/<sha256>.<ext>`; PDFs sent to Claude as `document` blocks, images as `image` blocks | No local OCR needed. |
| Excel/PDF | Apache POI (xlsx export), PDFBox (test-fixture generation) | |

Ports: backend `127.0.0.1:8710`, frontend `127.0.0.1:3710` (localhost only).

## Layout
```
run.sh                     start backend + frontend
.tool-versions             nodejs 22.16.0
backend/
  build.gradle, gradlew    Gradle wrapper
  src/main/java/com/account0/
    common/                Tenant, Money (paise), AuditService, SettingsService, SystemController (/health)
    llm/                   LlmGateway (cap + usage ledger), AnthropicHttpTransport, LlmController
    company/               company profile + GSTIN validation
    documents/             upload, storage, watched folder, batches, Google source stubs
    extraction/            prompt, Claude call, JSON parsing, deterministic checks
    invoice/               invoices, review/confirm, counterparty rules, locks
    ledger/                double-entry posting + trial balance + CSV
    gst/                   Gstr3bCalculator, Gstr1Calculator, explain, exports, CA pack, filings
  src/main/resources/db/migration/V*__*.sql
  src/test/java/...        JUnit (fake LLM transport) + fixtures generator
frontend/
  app/<route>/page.tsx     one folder per screen
  lib/                     api(), format (paise→₹)
  tests/*.test.ts          node:test unit checks
fixtures/invoices/         generated fake Indian GST invoices (PDF) used by gates
scripts/gate-*.sh          phase gate scripts (boot app, curl, assert)
spec/                      this folder
data/                      runtime (gitignored): db, documents, inbox
```

## Conventions
- Every table has `tenant_id`; every query filters on `Tenant.current()`.
- Money: integer paise (`*_paise` columns / JSON fields). GST rates: percent as decimal (e.g. `18`), validated against the slab list {0, 0.1, 0.25, 1.5, 3, 5, 12, 18, 28, 40}.
- Tax computation is pure functions over confirmed invoices (`gst/*Calculator`), unit-tested, with each output figure carrying `rule`, `ref` (legal citation) and `sourceInvoiceIds`.
- LLM: only via `LlmGateway.call`; one call per document; usage row written for every attempt (OK / ERROR / BLOCKED_CAP).
- Audit: every user/system change of a figure goes through `AuditService` (old → new).
- Stubs: code and UI label them `STUB:`; API responses include `"stub": true`.
- REST JSON under `/api/**`; errors `{ "error": "..." }`.
- Commits: `phase-N: <desc>`; tests + gate script must pass before commit.
