-- Phase 1: company profile, documents, extraction, invoices, counterparty rules, ledger, filings/locks.
CREATE TABLE company_profile (
  tenant_id INTEGER PRIMARY KEY REFERENCES tenant(id),
  legal_name TEXT, trade_name TEXT, cin TEXT, gstin TEXT, pan TEXT, tan TEXT,
  state_code TEXT, address TEXT,
  fy_start_month INTEGER NOT NULL DEFAULT 4,
  gst_filing_frequency TEXT NOT NULL DEFAULT 'MONTHLY',
  lut_arn TEXT, lut_valid_from TEXT, lut_valid_to TEXT,
  updated_at TEXT
);
INSERT INTO company_profile (tenant_id) VALUES (1);

CREATE TABLE doc_batch (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  source TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE TABLE document (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  batch_id INTEGER REFERENCES doc_batch(id),
  filename TEXT NOT NULL,
  mime TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  stored_path TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  source TEXT NOT NULL,             -- UPLOAD | FOLDER | GMAIL_STUB | DRIVE_STUB
  status TEXT NOT NULL,             -- QUEUED | EXTRACTING | NEEDS_REVIEW | CONFIRMED | FAILED | DUPLICATE | REJECTED
  error TEXT,
  duplicate_of INTEGER,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);
CREATE INDEX idx_document_sha ON document(tenant_id, sha256);
CREATE INDEX idx_document_status ON document(tenant_id, status);

CREATE TABLE extraction (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  document_id INTEGER NOT NULL REFERENCES document(id),
  model TEXT NOT NULL,
  raw_json TEXT NOT NULL,
  checks_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE TABLE invoice (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  document_id INTEGER REFERENCES document(id),
  status TEXT NOT NULL DEFAULT 'DRAFT',      -- DRAFT | CONFIRMED | REJECTED
  direction TEXT,                             -- SALE | PURCHASE
  doc_type TEXT NOT NULL DEFAULT 'INVOICE',   -- INVOICE | CREDIT_NOTE | DEBIT_NOTE
  invoice_no TEXT, invoice_date TEXT, period TEXT,
  counterparty_name TEXT, counterparty_gstin TEXT, counterparty_country TEXT,
  place_of_supply TEXT,                       -- 2-digit state code, or '96' (other country)
  supply_type TEXT,                           -- see SupplyType enum
  reverse_charge INTEGER,                     -- 0/1, user-confirmed
  itc_eligible INTEGER,                       -- purchases: 0/1, user-confirmed
  currency TEXT NOT NULL DEFAULT 'INR',
  foreign_amount TEXT, fx_rate TEXT,
  taxable_paise INTEGER NOT NULL DEFAULT 0,
  cgst_paise INTEGER NOT NULL DEFAULT 0, sgst_paise INTEGER NOT NULL DEFAULT 0,
  igst_paise INTEGER NOT NULL DEFAULT 0, cess_paise INTEGER NOT NULL DEFAULT 0,
  total_paise INTEGER NOT NULL DEFAULT 0,
  category TEXT, category_suggested INTEGER NOT NULL DEFAULT 0,
  tax_treatment_source TEXT,                  -- NONE | RULE | USER
  original_invoice_no TEXT,                   -- credit/debit notes
  notes TEXT,
  flags_json TEXT NOT NULL DEFAULT '[]',
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  confirmed_at TEXT
);
CREATE INDEX idx_invoice_period ON invoice(tenant_id, period, status);
CREATE INDEX idx_invoice_dup ON invoice(tenant_id, counterparty_gstin, invoice_no);

CREATE TABLE invoice_line (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  invoice_id INTEGER NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
  line_no INTEGER NOT NULL,
  description TEXT, hsn_sac TEXT, uqc TEXT, quantity TEXT,
  gst_rate TEXT,
  taxable_paise INTEGER NOT NULL DEFAULT 0,
  cgst_paise INTEGER NOT NULL DEFAULT 0, sgst_paise INTEGER NOT NULL DEFAULT 0,
  igst_paise INTEGER NOT NULL DEFAULT 0, cess_paise INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE counterparty_rule (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  match_key TEXT NOT NULL,                    -- GSTIN, or 'NAME:<normalised name>' for unregistered/foreign
  direction TEXT NOT NULL,
  display_name TEXT,
  category TEXT,
  supply_type TEXT,
  reverse_charge INTEGER,
  itc_eligible INTEGER,
  default_gst_rate TEXT,
  default_hsn_sac TEXT,
  tds_section TEXT,
  times_applied INTEGER NOT NULL DEFAULT 0,
  learned_from_invoice_id INTEGER,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, match_key, direction)
);

CREATE TABLE journal (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  entry_date TEXT NOT NULL,
  period TEXT NOT NULL,
  ref_type TEXT NOT NULL, ref_id INTEGER NOT NULL,
  narration TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);
CREATE INDEX idx_journal_ref ON journal(tenant_id, ref_type, ref_id);
CREATE TABLE journal_line (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  journal_id INTEGER NOT NULL REFERENCES journal(id) ON DELETE CASCADE,
  account TEXT NOT NULL,
  debit_paise INTEGER NOT NULL DEFAULT 0,
  credit_paise INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE filing (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  return_type TEXT NOT NULL,                  -- GSTR1 | GSTR3B | ...
  period TEXT NOT NULL,                       -- YYYY-MM
  arn TEXT, filed_on TEXT, filed_by TEXT,
  locked INTEGER NOT NULL DEFAULT 1,
  notes TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, return_type, period)
);
