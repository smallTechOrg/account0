-- Phase 6: compliance calendar (user status per due item) and month-close manual ticks.
CREATE TABLE compliance_status (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  code TEXT NOT NULL, period TEXT NOT NULL,
  status TEXT NOT NULL,              -- DONE | NA
  done_on TEXT, ref TEXT, note TEXT,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, code, period)
);
CREATE TABLE close_tick (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  period TEXT NOT NULL, item_key TEXT NOT NULL,
  state TEXT NOT NULL,               -- DONE | NA
  note TEXT,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, period, item_key)
);
