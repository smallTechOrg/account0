-- Phase 4: TDS register (26Q data) and payroll / Sec 192 (24Q data).
CREATE TABLE tds_entry (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  invoice_id INTEGER NOT NULL REFERENCES invoice(id),
  section TEXT NOT NULL,                 -- 194C | 194J_PROF | ... | NONE (user says not applicable)
  payee_type TEXT NOT NULL DEFAULT 'COMPANY_OR_FIRM',
  payee_pan TEXT,
  payee_name TEXT,
  fy TEXT NOT NULL,                      -- 2026-27
  payment_date TEXT NOT NULL,            -- invoice date = date of credit (earlier of credit/payment, Sec 194 timing)
  base_paise INTEGER NOT NULL DEFAULT 0, -- invoice value excl. GST (Circular 23/2017)
  rate_bp INTEGER NOT NULL DEFAULT 0,
  tds_paise INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'PENDING',-- DEDUCT | BELOW_THRESHOLD | NOT_APPLICABLE
  reason TEXT,
  deposited_on TEXT, challan_no TEXT, bsr_code TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, invoice_id)
);
CREATE INDEX idx_tds_fy ON tds_entry(tenant_id, fy);

CREATE TABLE payroll_line (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  employee_id TEXT NOT NULL, employee_name TEXT, pan TEXT,
  month TEXT NOT NULL,                   -- YYYY-MM
  fy TEXT NOT NULL,
  gross_paise INTEGER NOT NULL,
  regime TEXT NOT NULL DEFAULT 'NEW',
  projected_annual_paise INTEGER, annual_tax_paise INTEGER,
  tds_paise INTEGER NOT NULL DEFAULT 0,
  needs_ca_review INTEGER NOT NULL DEFAULT 0,
  note TEXT,
  deposited_on TEXT, challan_no TEXT,
  UNIQUE (tenant_id, employee_id, month)
);
CREATE INDEX idx_payroll_fy ON payroll_line(tenant_id, fy);
