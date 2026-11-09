-- Phase 3: CA-filed return scrutiny, GSTR-2B import + ITC matching.
CREATE TABLE filed_return (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  return_type TEXT NOT NULL,          -- GSTR3B
  period TEXT NOT NULL,
  source TEXT NOT NULL,               -- PORTAL_JSON | MANUAL | PDF_LLM
  figures_json TEXT NOT NULL,         -- normalised: {"3.1a": {"taxable":..,"igst":..,...}} in paise
  confirmed INTEGER NOT NULL DEFAULT 0,
  document_id INTEGER REFERENCES document(id),
  explanation TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);
CREATE INDEX idx_filed_return ON filed_return(tenant_id, return_type, period);

CREATE TABLE gstr2b_import (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  period TEXT NOT NULL,
  gstin TEXT,
  filename TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE TABLE gstr2b_doc (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  import_id INTEGER NOT NULL REFERENCES gstr2b_import(id) ON DELETE CASCADE,
  period TEXT NOT NULL,
  section TEXT NOT NULL,              -- B2B | CDNR
  ctin TEXT NOT NULL, trade_name TEXT,
  doc_no TEXT NOT NULL, doc_date TEXT, doc_type TEXT,
  supplier_filed_on TEXT, supplier_period TEXT,
  reverse_charge INTEGER NOT NULL DEFAULT 0,
  itc_available INTEGER NOT NULL DEFAULT 1,
  taxable_paise INTEGER NOT NULL DEFAULT 0, igst_paise INTEGER NOT NULL DEFAULT 0, cgst_paise INTEGER NOT NULL DEFAULT 0,
  sgst_paise INTEGER NOT NULL DEFAULT 0, cess_paise INTEGER NOT NULL DEFAULT 0,
  matched_invoice_id INTEGER, match_status TEXT, match_note TEXT
);
CREATE INDEX idx_2b_period ON gstr2b_doc(tenant_id, period);
