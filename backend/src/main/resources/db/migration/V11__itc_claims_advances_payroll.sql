-- Phase 11
-- #1 ITC claim tracking. A row is the claim of one purchase document's ITC in one return period. Rows are written when a GSTR-3B
-- filing locks a period (snapshot of that period's 4A(5)); while that filing stays locked, the row is authoritative: the document is
-- claimed in that period only. Unlocked periods are computed live (earliest eligible period wins).
CREATE TABLE itc_claim (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  invoice_id INTEGER NOT NULL REFERENCES invoice(id),
  period TEXT NOT NULL,
  basis TEXT NOT NULL,                 -- PROVISIONAL (books, no 2B) | 2B | 2B_ACCEPTED
  doc2b_id INTEGER,
  invoice_no TEXT, invoice_date TEXT, counterparty_name TEXT, doc_type TEXT, itc_eligible INTEGER,
  igst_paise INTEGER NOT NULL DEFAULT 0, cgst_paise INTEGER NOT NULL DEFAULT 0, sgst_paise INTEGER NOT NULL DEFAULT 0, cess_paise INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, invoice_id)
);
CREATE INDEX idx_itc_claim_period ON itc_claim(tenant_id, period);

-- #2 user decision on a POSSIBLE_MATCH / AMOUNT_MISMATCH GSTR-2B line. Keyed on the 2B line's natural key so it survives a re-import.
CREATE TABLE itc_2b_decision (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  period TEXT NOT NULL,
  ctin TEXT NOT NULL,
  doc_no_norm TEXT NOT NULL,
  doc_type TEXT NOT NULL DEFAULT 'INVOICE',
  invoice_id INTEGER NOT NULL REFERENCES invoice(id),
  decision TEXT NOT NULL,              -- ACCEPTED | REJECTED
  reason TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, period, ctin, doc_no_norm, doc_type)
);

-- #3 GSTR-1 Table 11: advances received (11A) and adjusted against invoices (11B).
CREATE TABLE advance_receipt (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  receipt_date TEXT NOT NULL,
  period TEXT NOT NULL,
  customer_name TEXT NOT NULL,
  customer_gstin TEXT,
  place_of_supply TEXT NOT NULL,       -- state code, or 96 (other countries) for exports
  kind TEXT NOT NULL,                  -- DOMESTIC | EXPORT_LUT | EXPORT_PAID
  supply_kind TEXT NOT NULL DEFAULT 'SERVICES', -- SERVICES | GOODS (no tax on advances for goods: Notification 66/2017-CT)
  gst_rate TEXT NOT NULL,
  gross_paise INTEGER NOT NULL,        -- amount received (tax inclusive)
  taxable_paise INTEGER NOT NULL DEFAULT 0,
  igst_paise INTEGER NOT NULL DEFAULT 0, cgst_paise INTEGER NOT NULL DEFAULT 0, sgst_paise INTEGER NOT NULL DEFAULT 0, cess_paise INTEGER NOT NULL DEFAULT 0,
  bank_txn_id INTEGER REFERENCES bank_txn(id),
  notes TEXT,
  status TEXT NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DELETED
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);
CREATE INDEX idx_advance_period ON advance_receipt(tenant_id, period);
CREATE TABLE advance_adjustment (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  advance_id INTEGER NOT NULL REFERENCES advance_receipt(id),
  invoice_id INTEGER NOT NULL REFERENCES invoice(id),
  period TEXT NOT NULL,                -- period of the invoice
  gross_paise INTEGER NOT NULL,
  taxable_paise INTEGER NOT NULL DEFAULT 0,
  igst_paise INTEGER NOT NULL DEFAULT 0, cgst_paise INTEGER NOT NULL DEFAULT 0, sgst_paise INTEGER NOT NULL DEFAULT 0, cess_paise INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

-- #4 Rules 42/43: how a purchase's credit is used. NULL/COMMON = common to taxable and exempt supplies; TAXABLE = only taxable
-- (incl. zero-rated) supplies; EXEMPT = only exempt supplies (credit not available, reversed in full). capital_goods = Rule 43 instead of 42.
ALTER TABLE invoice ADD COLUMN itc_use TEXT;
ALTER TABLE invoice ADD COLUMN capital_goods INTEGER NOT NULL DEFAULT 0;
-- #6 194I: rent period covered by the invoice (each month is tested against the ₹50,000 per-month threshold).
ALTER TABLE invoice ADD COLUMN rent_from TEXT;
ALTER TABLE invoice ADD COLUMN rent_to TEXT;

-- #5 bank lines matched to something other than an invoice, and export-receipt splits.
ALTER TABLE bank_txn ADD COLUMN match_kind TEXT;            -- NULL = invoice | SALARY | PF | ESI | TDS_CHALLAN | ADVANCE
ALTER TABLE bank_txn ADD COLUMN match_ref TEXT;             -- payroll month YYYY-MM | challan no | advance id
ALTER TABLE bank_txn ADD COLUMN bank_charges_paise INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bank_txn ADD COLUMN forex_gain_paise INTEGER NOT NULL DEFAULT 0;  -- negative = loss

-- #5 PF / ESI per employee-month.
ALTER TABLE payroll_line ADD COLUMN pf_wage_paise INTEGER;
ALTER TABLE payroll_line ADD COLUMN pf_employee_paise INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payroll_line ADD COLUMN pf_employer_paise INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payroll_line ADD COLUMN pf_admin_paise INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payroll_line ADD COLUMN esi_employee_paise INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payroll_line ADD COLUMN esi_employer_paise INTEGER NOT NULL DEFAULT 0;
