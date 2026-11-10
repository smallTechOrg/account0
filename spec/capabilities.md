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
- Assumed (P10, tax audit): GSTR-3B set-off — IGST credit is exhausted first (IGST, then CGST/SGST); the CGST/SGST split of residual IGST credit goes first to the head whose own credit falls short, so cash is minimised (Rule 88A; Circular 98/17/2019-GST). Replaces the fixed IGST->CGST->SGST order.
- Assumed (P10): 4A(5) — with a GSTR-2B imported for the period, 4A(5) = 2B lines of that 2B period (B2B, B2BA, CDNR; credit notes negative) that MATCH a book purchase on every tax head and are itcavl=Y; mismatched/possible matches, itcavl=N lines and book invoices missing from 2B are left out with a note (a user "accept" for POSSIBLE_MATCH/AMOUNT_MISMATCH is not built — resolve the mismatch first). Without a 2B, 4A(5) = books, labelled PROVISIONAL. ISD lines feed 4A(4); IMPG lines are shown as a note only (4A(1) stays from Bill-of-Entry purchases). Sec 16(4): an invoice's ITC is excluded from the return period of November after its FY end, or later (a late-filed October return is not detected). An earlier-period invoice claimed provisionally (no 2B) and later matched in another month's 2B can be counted twice — import 2B every month.
- Assumed (P10): RCM time of supply (Sec 13(3)) = earlier of the payment date (field `rcm_paid_on`, or the first matched bank payment) and invoice date + 61 days ("the date immediately after sixty days"); the RCM tax and its 4A(2)/4A(3) credit go to the 3B of that month. Associated-enterprise imports (earlier of book entry or payment) are not distinguished. Rule 34 exchange rate is only flagged (INFO), not recomputed. Gate seeds now state the Atlas payment date (card payment on the invoice date).
- Assumed (P10): 194I uses a ₹50,000 per month (or part of a month) threshold from 1 Apr 2025, testing each invoice as the rent for the month of its date (rent invoiced annually in advance is not apportioned). 194J_DIR (director remuneration, 194J(1)(ba)) = 10% with no threshold. No-PAN rate per section: 5% for 194Q, 20% otherwise (206AA / s.397(2) — exact new-Act mapping: verify with CA). Salary without PAN: higher of 20% of the month's salary and the normal TDS, nil if no tax is payable on the projected income (flagged for CA). s.393 payment codes shown beside sections (1006, 1008, 1009, 1023/1024, 1027, 1028) come from secondary sources — verify with CA.
- Assumed (P10): Income-tax Rules 2026 form names by FY: from FY 2026-27, 24Q->Form 138, 26Q->Form 140, 27Q->Form 144, Form 16->Form 130, Form 16A->Form 131 (Form 131 confirmed by the Income Tax Department FAQ); Q4 FY 2025-26 returns and Form 16 for FY 2025-26 keep the old names. Due dates unchanged. Calendar item codes (TDS_26Q etc.) are unchanged for continuity.
- Assumed (P10): DIR-3 KYC (G.S.R. 943(E), effective 31 Mar 2026): annual 30 Sep up to the FY 2025-26 calendar; from FY 2026-27, once every 3 FYs by 30 June: next due FY = last-KYC FY + 3 (directors recorded via Calendar > Directors, stored in setting `company.directors`). With no directors recorded, one "if applicable" item on 30 June. The first cycle after the change is ambiguous in secondary sources — UI says verify with CA. Event-based KYC (change of mobile/e-mail/address within 30 days) is text only.
- Assumed (P10): new calendar items: LUT renewal RFD-11 by 31 Mar (ALWAYS once a confirmed LUT export/SEZ sale exists or setting `company.exports_under_lut=true`), Form 144/27Q quarterly, MSME Form-1 (30 Apr / 31 Oct), GSTR-9C (31 Dec) as "if applicable"; Form 15CA/15CB as an "if applicable" event dated on each confirmed foreign-vendor invoice. PF/ESI cite the Code on Social Security 2020 (in force 21 Nov 2025; verify with CA); due dates unchanged.
- Assumed (P10): GSTR-1 — Table 13 groups documents by nature (1 invoices, 4 debit notes, 5 credit notes) and by series prefix (everything before the trailing number), sorted numerically, totnum = documents known to Account0 (a gap in the series is flagged). Table 8 buckets by buyer GSTIN (B2B/B2C) and place of supply; invoice field `nil_type` (NIL/EXEMPT, default reported as EXEMPT with a note). Nil/exempt sales are in the HSN summary. JSON export is refused while any HSN/SAC is missing or not 4/6/8 digits (CA pack then carries a NOT-EXPORTED note). A credit/debit note to an unregistered inter-state buyer whose original invoice (by `original_invoice_no`) exceeded ₹1 lakh goes to CDNUR type B2CL. 9B/CDNUR summaries net credit notes negative. HSN item numbering (num 1, 2, ...) and B2B item num (101, 201...) unchanged (portal convention uncertain).
- Assumed (P10): 3.2 includes inter-state B2B sales flagged `buyer_kind` = COMPOSITION or UIN; Table 5 splits inter/intra by supplier GSTIN state vs ours (no GSTIN = intra); a missing company GSTIN is an error on the 3B page. Rules 42/43 (common credit with exempt sales) and Rule 37 (180-day unpaid) are NOT computed: 3B shows notes (Rule 37 only when bank statements exist) — verify with CA. GST 2.0 (22 Sep 2025): 12%/28% lines dated on/after that date get a WARN.
- Assumed (P10): ledger — import of goods credits the vendor with value excluding customs IGST/cess and "Payable: Customs (IGST/cess on Bill of Entry)" with the duty; outstanding receivables/payables are reduced by confirmed credit notes linked by `original_invoice_no` (same party). PF/ESI payroll postings, salary-payable clearing, forex gain/loss and bank charges, Table 11 advances remain unbuilt.

- Assumed (P11, #1 ITC claimed once): each forward-charge domestic purchase document (invoice/credit/debit note) has ONE claim period for 4A(5). Candidates: its own period while no GSTR-2B is imported for that period (PROVISIONAL, from books) and every 2B period in which a 2B line MATCHED it or the user ACCEPTED the line. The earliest candidate wins. Recording a GSTR-3B filing (or re-locking it) snapshots that period's 4A(5) into `itc_claim`; while the GSTR-3B filing stays locked the snapshot is authoritative (the period's 4A(5) shows the filed claims, mode FILED, and every other period leaves those documents out). Unlocking makes the period live again; re-locking re-snapshots. Periods locked before P11 have no snapshot and are computed live. The 3B response carries `itcClaims` (reconciliation: candidate periods, where claimed, basis, filed) shown under 4A on /gst and on /itc. ISD (4A(4)) lines are not claim-tracked (they have no book document).
- Assumed (P11, #2): the user may ACCEPT or REJECT a POSSIBLE_MATCH / AMOUNT_MISMATCH 2B line (reason required, audited as entity `gstr2b_doc` action `decision`; CLEAR undoes). The decision is keyed on period + supplier GSTIN + normalised document number + document type (so it survives a 2B re-import) and applies only while the line is still paired with the same book invoice. An accepted line is claimed at the 2B tax by head (not the books' figures); a rejected line is left out with a note; decided lines no longer raise ITC alerts.
- Assumed (P11, #3 advances): advances are entered on /advances (optionally linked to an unmatched bank receipt, which is then MATCHED with match_kind ADVANCE; the advance must equal the receipt). The amount received is tax-inclusive; tax = amount x rate/(100+rate) (intra-state: CGST = SGST computed on rate/2 each). Taxed at receipt only for DOMESTIC (and EXPORT_PAID: IGST) advances for SERVICES (Sec 13(2)); GOODS advances carry no tax (Notification 66/2017-CT); EXPORT_LUT advances carry no tax and are not reported (place of supply 96). GSTR-1 Table 11A (`at`) = advances received in the period less the part adjusted by invoices of the same period (an advance and its invoice in the same month are not reported); Table 11B (`txpd`) = adjustments in the period of advances received earlier, with the tax apportioned by amount (the last adjustment takes the remainder). 3B 3.1(a) += 11A and -= 11B (value and tax). `ad_amt` is the tax-exclusive value (portal shape best effort — validate in the offline tool). Ledger: Dr Bank (or "Bank / cash (advance receipts not matched to a bank line)") / Cr "Advance from customer: <name>"; adjustment Dr Advance / Cr Receivable. The GST on an advance is not posted separately in the ledger (it is booked with the invoice). Receipt vouchers (Rule 50) and refund vouchers are not generated.
