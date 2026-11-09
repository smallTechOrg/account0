package com.account0.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SettingsService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public SettingsService(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    public String get(String key, String dflt) {
        List<String> v = jdbc.queryForList("SELECT value FROM setting WHERE tenant_id=? AND key=?", String.class, Tenant.current(), key);
        return v.isEmpty() ? dflt : v.get(0);
    }
    public void set(String key, String value) {
        String old = get(key, null);
        jdbc.update("INSERT INTO setting(tenant_id,key,value) VALUES (?,?,?) ON CONFLICT(tenant_id,key) DO UPDATE SET value=excluded.value", Tenant.current(), key, value);
        audit.record("user", "setting", key, "update", "value", old, value, null);
    }
    public Map<String, String> all() {
        Map<String, String> m = new java.util.TreeMap<>();
        jdbc.query("SELECT key,value FROM setting WHERE tenant_id=?", rs -> { m.put(rs.getString(1), rs.getString(2)); }, Tenant.current());
        return m;
    }
}
