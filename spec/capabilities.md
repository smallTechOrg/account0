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
- Gmail / Google Drive sources: interface only, **[stub]** until P8 (user sets up OAuth).
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

## C7 CA scrutiny [planned P3]
Upload CA's filed 3B (JSON / PDF / typed figures) → line-by-line diff vs recompute → generated
"questions to ask your CA" + plain-language explanation of the filed return.

## C8 GSTR-2B + ITC match + alerts [planned P3]
Import 2B JSON; match purchase invoices; alerts: vendor not filed / ITC at risk, duplicates, RCM unpaid.

## C9 TDS + payroll [planned P4]
194C/194J/194I/194H/194Q + 192 salary; thresholds; 26Q/24Q data; payroll import (CSV).

## C10 Bank statement import + reconciliation [planned P5]

## C11 Compliance calendar + month-close checklist [planned P6]
GST, TDS, PF/ESI, ROC (AOC-4, MGT-7/7A, DIR-3 KYC, ADT-1), advance tax, ITR-6 — with status.

## C12 Learning layer [planned P7; inline "why" notes from P2]
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
