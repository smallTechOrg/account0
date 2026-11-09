-- Phase 0: core tables. Every business table carries tenant_id (multi-tenant ready, single tenant 1 for now).
CREATE TABLE tenant (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);
INSERT INTO tenant (id, name) VALUES (1, 'default');

CREATE TABLE setting (
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  key TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (tenant_id, key)
);
INSERT INTO setting (tenant_id, key, value) VALUES (1, 'llm.monthly_cap_usd', '20.00');

CREATE TABLE llm_call (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  model TEXT NOT NULL,
  purpose TEXT NOT NULL,
  ref TEXT,
  input_tokens INTEGER NOT NULL DEFAULT 0,
  output_tokens INTEGER NOT NULL DEFAULT 0,
  cost_usd_micros INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL,
  error TEXT,
  duration_ms INTEGER
);
CREATE INDEX idx_llm_call_month ON llm_call(tenant_id, created_at);

CREATE TABLE audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  actor TEXT NOT NULL,
  entity TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  action TEXT NOT NULL,
  field TEXT,
  old_value TEXT,
  new_value TEXT,
  note TEXT
);
CREATE INDEX idx_audit_entity ON audit_log(tenant_id, entity, entity_id);
