package com.account0.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String actor, String entity, Object entityId, String action, String field, Object oldV, Object newV, String note) {
        jdbc.update("INSERT INTO audit_log(tenant_id, actor, entity, entity_id, action, field, old_value, new_value, note) VALUES (?,?,?,?,?,?,?,?,?)",
                Tenant.current(), actor, entity, String.valueOf(entityId), action, field,
                oldV == null ? null : String.valueOf(oldV), newV == null ? null : String.valueOf(newV), note);
    }

    /** Records one row per changed field. */
    public void diff(String actor, String entity, Object entityId, Map<String, ?> before, Map<String, ?> after) {
        for (var e : after.entrySet()) {
            Object o = before == null ? null : before.get(e.getKey());
            if (!Objects.equals(str(o), str(e.getValue()))) record(actor, entity, entityId, "update", e.getKey(), o, e.getValue(), null);
        }
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    public List<Map<String, Object>> list(String entity, String entityId, int limit) {
        if (entity == null) return jdbc.queryForList("SELECT * FROM audit_log WHERE tenant_id=? ORDER BY id DESC LIMIT ?", Tenant.current(), limit);
        if (entityId == null) return jdbc.queryForList("SELECT * FROM audit_log WHERE tenant_id=? AND entity=? ORDER BY id DESC LIMIT ?", Tenant.current(), entity, limit);
        return jdbc.queryForList("SELECT * FROM audit_log WHERE tenant_id=? AND entity=? AND entity_id=? ORDER BY id DESC LIMIT ?", Tenant.current(), entity, entityId, limit);
    }
}
