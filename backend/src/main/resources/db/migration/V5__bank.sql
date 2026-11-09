-- Phase 5: bank statements and reconciliation.
CREATE TABLE bank_statement (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  account_label TEXT NOT NULL,
  filename TEXT,
  mapping_json TEXT,
  rows_total INTEGER NOT NULL DEFAULT 0, rows_new INTEGER NOT NULL DEFAULT 0, rows_duplicate INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);
CREATE TABLE bank_txn (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  statement_id INTEGER NOT NULL REFERENCES bank_statement(id) ON DELETE CASCADE,
  account_label TEXT NOT NULL,
  txn_date TEXT NOT NULL,
  description TEXT, ref TEXT,
  debit_paise INTEGER NOT NULL DEFAULT 0, credit_paise INTEGER NOT NULL DEFAULT 0,
  balance_paise INTEGER,
  dedupe_key TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'UNMATCHED',   -- UNMATCHED | SUGGESTED | MATCHED | IGNORED
  matched_invoice_id INTEGER REFERENCES invoice(id),
  match_source TEXT,                          -- AUTO | USER
  match_score INTEGER, match_note TEXT,
  ignore_reason TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, dedupe_key)
);
CREATE INDEX idx_bank_txn_status ON bank_txn(tenant_id, status, txn_date);
CREATE INDEX idx_bank_txn_inv ON bank_txn(tenant_id, matched_invoice_id);
