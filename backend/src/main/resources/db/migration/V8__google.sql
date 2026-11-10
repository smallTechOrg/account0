-- Phase 8: Google OAuth tokens (encrypted at rest) and de-duplication of externally fetched files.
CREATE TABLE oauth_token (
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  provider TEXT NOT NULL,                 -- google
  account_email TEXT,
  access_token_enc TEXT, refresh_token_enc TEXT,
  expires_at TEXT, scope TEXT,
  connected_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  PRIMARY KEY (tenant_id, provider)
);
CREATE TABLE oauth_state (
  state TEXT PRIMARY KEY, code_verifier TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);
CREATE TABLE external_seen (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tenant_id INTEGER NOT NULL REFERENCES tenant(id),
  source TEXT NOT NULL,                   -- GMAIL | DRIVE
  external_id TEXT NOT NULL,
  filename TEXT, document_id INTEGER,
  seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (tenant_id, source, external_id)
);
