# Account0 — Capabilities

Local-first AI back-office and compliance co-pilot for an Indian Private Limited company.
Reference user: monthly GSTR-1/3B filer; SaaS export of services under LUT; foreign vendors under
reverse charge (RCM, import of services); domestic B2B/B2C sales; < 50 invoices/month; < 10 employees.
The founder's CA files the returns. The founder wants to (a) keep organised books, (b) recompute and
check what the CA files, (c) learn the process well enough to file it themselves later.

Status legend: **[built]** working · **[stub]** clearly labelled placeholder in the UI/API · **[planned]** roadmap.

## C1 Company profile [built P1]
Legal name, CIN, GSTIN, PAN, TAN, state code (derived from GSTIN), FY start, GST filing frequency, LUT ARN/validity.
Validation: GSTIN format + check digit; PAN = GSTIN chars 3–12; state code = GSTIN chars 1–2.

## C2 Invoice inbox + extraction [built P1]
- Upload one or many PDF/PNG/JPG invoices (a batch), with a progress bar for the batch.
- Watched local folder (`data/inbox/`) is polled; new files are imported automatically.
- Gmail / Google Drive sources: **[built P8, unconnected until you add Google credentials; only tested against a mock]**.
- Duplicate detection by file SHA-256 and by (counterparty GSTIN, invoice no).
- Claude extracts, in ONE call per document: supplier/recipient name+GSTIN+country, invoice no/date,
  currency, line items (description, HSN/SAC, taxable value, rate, CGST/SGST/IGST/cess), totals,
  place of supply, RCM wording on the face of the invoice — each with confidence + evidence text.
- Deterministic post-checks: GSTIN checksum, Σlines = totals, taxable+tax = total, intra-vs-inter-state
  consistency with tax heads, date in plausible range, direction (sale vs purchase) from our GSTIN.

## C3 Review queue [built P1]
- Source document side by side with the extracted fields; low-confidence / failed-check fields highlighted.
- **Tax treatment is never guessed.** Supply type (B2B, B2C, export-LUT, import-of-service-RCM, …),
  reverse charge, GST rates and ITC eligibility must be confirmed by the user on every invoice.
  A confirmed counterparty rule pre-fills them (labelled "from your rule") but the user still confirms.
- Categories (expense head / income head) may be best-guess and are flagged as such.
- Confirming posts the invoice to the ledger and (optionally) saves/updates a counterparty rule.

## C4 Memory [built P1 rules; P2 filings]
- Counterparty rules: learned category + tax treatment per GSTIN (or name for foreign vendors), only
  ever written from a user confirmation.
- Filing history: return, period, ARN, filed date; filed periods are **locked** (no edits to invoices in
  a locked period without an explicit unlock, which is audited).

## C5 Ledger / books [built P1]
Double-entry journal generated deterministically from confirmed invoices. Trial balance; journal CSV export.

## C6 GST computation [built P2]
- GSTR-3B (3.1 a–e, 3.2, 4A/4B/4D, 6.1 tax payable with set-off order) and GSTR-1 (B2B, B2CL, B2CS,
  EXP, CDNR, HSN summary, document summary) — plain deterministic Java, never LLM math.
- Every figure is clickable → rule applied (with legal reference) + the source invoices.
- Exports: GSTR-1 JSON (portal offline-tool shape), Excel workbook, monthly CA pack (zip).

## C7 CA scrutiny [built P3]
Upload CA's filed 3B (JSON / PDF / typed figures) → line-by-line diff vs recompute → generated
"questions to ask your CA" + plain-language explanation of the filed return.

## C8 GSTR-2B + ITC match + alerts [built P3]
Import 2B JSON; match purchase invoices; alerts: vendor not filed / ITC at risk, duplicates, RCM unpaid.

## C9 TDS + payroll [built P4]
194C/194J/194I/194H/194Q + 192 salary; thresholds; 26Q/24Q data; payroll import (CSV).

## C10 Bank statement import + reconciliation [built P5]
CSV/XLSX import with auto-detected (overridable) column mapping, idempotent re-import, running-balance continuity check, scored matching to confirmed invoices (amount, date window, invoice no, vendor name; receipts net of TDS recognised), unambiguous strong matches auto-applied, the rest suggested; manual match/unmatch/ignore-with-reason; outstanding receivables/payables with ageing; alerts. No journal posting of payments yet (deferred).

## C11 Compliance calendar + month-close checklist [built P6]
GST, TDS, PF/ESI, ROC (AOC-4, MGT-7/7A, DIR-3 KYC, ADT-1), advance tax, ITR-6 — with status.

## C12 Learning layer [built P7]
Walkthroughs using the user's data, inline "why" notes, Q&A with citations (CGST Act sections, rules,
CBIC circulars), DIY coaching mode.

## C13 Audit trail [built P0+]
Every change to a figure (invoice field, rule, setting, lock) recorded with old/new values and actor.

## C14 LLM spend [built P0]
Every call logged with model, purpose, tokens and USD cost; monthly running total; monthly cap
(setting `llm.monthly_cap_usd`, default $20) blocks calls once reached.

## Assumptions
- Assumed: single tenant (id 1), no login; all tables carry `tenant_id`.
- Assumed: amounts stored as integer paise; foreign-currency invoices are recorded in INR using the
  rate the user confirms (extraction proposes the invoice's own INR figure if printed).
- Assumed: monthly filer (not QRMP); GSTR-1 B2CL threshold = invoice value > ₹1,00,000 inter-state
  to unregistered (threshold reduced from ₹2.5 lakh w.e.f. 1 Aug 2024, Notification 12/2024-CT).
- Assumed: export of services under LUT is reported in 3B 3.1(b) and GSTR-1 table 6A as `WOPAY`.
- Assumed: import of services from foreign vendors → IGST under RCM (Sec 5(3) IGST Act), reported in
  3B 3.1(d), ITC claimed in 4A(2) "import of services" when eligible (user confirms eligibility).
- Assumed: LLM pricing per Anthropic list (cached 2026-11-03): Sonnet 5.5 $2/$10, Haiku 5.5 $0.10/$0.50 per MTok.
- Assumed (P3): filed-3B comparison tolerance ₹1 per figure; the latest *confirmed* filed 3B for a period is the one used by alerts. PDF transcription is never trusted until the user confirms it. 2B matching key = vendor GSTIN + normalised invoice number (upper-case, punctuation/leading zeros stripped); fuzzy fallback = same GSTIN + same tax (±₹1) + similar number (flagged POSSIBLE_MATCH, never auto-accepted). RCM purchases are excluded from 2B matching (they do not appear in 2B ITC).
- Assumed (P4): TDS base = invoice value excluding GST (CBDT Circular 23/2017); TDS timing = invoice date (credit). Rates/thresholds for 194C/194J/194I/194H/194Q as for FY 2025-26 onwards; Income-tax Act 1961 section numbers used (Income-tax Act 2025 renumbers — confirm with CA). Payee PAN defaults from vendor GSTIN; payee type from PAN 4th letter (P/H = individual/HUF) unless the user overrides. No PAN -> 20% (Sec 206AA). Sec 195 (non-resident) never computed. Salary: new regime FY 2025-26 slabs applied to later years; std deduction 75,000; 87A rebate with marginal relief; 4% cess; surcharge/old regime -> flagged for CA. TDS rounded to the nearest rupee.
- Assumed (P5): auto-match only if score >= 85 and the runner-up is >= 15 points lower; receipts up to 12% below the invoice are scored as "possibly net of TDS/charges" and only suggested unless invoice number + name both appear. Statement dates are day-first (Indian banks).
- Assumed (P6): calendar = obligations whose DUE DATE falls in the FY (so it carries last year's ROC/ITR/GSTR-9 filings); default AGM 30 Sep (editable via setting `company.agm_date`, moves ADT-1/AOC-4/MGT-7A); statutory un-extended dates for a monthly GST filer; PF/ESI/24Q/Form 16 only when payroll exists or `company.has_employees=true`; advance tax, ADT-1, DPT-3, GSTR-9, tax audit are "if applicable" (no alerts). Alerts: overdue (<=45 days back) HIGH, due within 7 days MEDIUM/LOW. Professional tax (state-specific) is not covered.
- Assumed (P7): the reference corpus (`backend/src/main/resources/corpus/corpus.json`, 57 entries) is my own paraphrase of provisions as understood for FY 2025-26 — NOT verified legal text; the UI says so. Q&A: deterministic retrieval; if nothing relevant, decline with no LLM call; otherwise ONE Sonnet call, citations validated against the retrieved ids (unknown ids stripped; uncited answers flagged "ungrounded"). Simplification vs. spec/agent.md: no tool loop - period/invoice facts are attached by code instead. DIY "readiness" = months where the user's recompute equalled the CA's confirmed filed 3B (needs >= 3 compared months).

## C15 Google sources [built P8, mock-tested only]
Read-only OAuth (PKCE, single-use state; scopes gmail.readonly + drive.readonly + userinfo.email) -> Gmail label poller and Drive folder poller feeding the normal inbox pipeline. Tokens are AES-GCM encrypted at rest (key from ACCOUNT0_SECRET_KEY or a random `data/.secret-key`), never returned by any API; disconnect revokes and deletes. Without ACCOUNT0_GOOGLE_CLIENT_ID/SECRET the UI shows the STUB box with setup steps. Assumed: the raw-HTTP Gmail v1 / Drive v3 calls are written from API docs and have NOT been run against live Google. De-dup: external id (+ md5 for Drive) and file SHA-256. Polls every 10 min when connected.
- Assumed (P9): bank receipts/payments, TDS and payroll are posted by `ledger/PostingService` (deterministic, idempotent, audited when content changes). Only MATCHED bank lines post (unmatched/ignored lines have an unknown other side). Vendor payment: Dr Payable / Cr Bank. TDS deducted: Dr Payable (vendor) / Cr "TDS payable: <section>"; challan: Dr TDS payable / Cr "Bank / cash (challan payments)" (challans are not tied to imported bank lines). Customer receipt net of TDS: Dr Bank (received) + Dr "TDS receivable (deducted by customers)" / Cr Receivable (received + TDS); the user states the withheld TDS on the match (auto-match cannot know it, so an un-annotated short receipt is treated as a part-payment). Payroll (per employee-month, dated month-end): Dr "Expense: Salaries" gross / Cr "TDS payable: 192 Salary" / Cr "Salary payable" net; salary payment from the bank is not posted (mark such lines ignored); old-regime employees post with TDS 0 and a narration flag.
- Assumed (P9): a cancelled sales document (issued, then cancelled) is marked by the user (`POST /api/invoices/{id}/cancel` with reason); it leaves all returns and the ledger and is counted as "cancelled" in GSTR-1 Table 13. Credit/debit notes on exports go to GSTR-1 `cdnur` with type EXPWOP/EXPWP (portal shape best-effort). QRMP: profile frequency QUARTERLY only adds a warning; figures stay monthly.
- Assumed (P9): the UI gate stubs extraction with `ACCOUNT0_LLM_STUB_FIXTURE` (StubFixtureTransport: returns a fixed extraction JSON, spends no tokens) - never set it in normal use.
