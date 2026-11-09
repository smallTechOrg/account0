package com.account0.invoice;

import com.account0.common.AuditService;
import com.account0.common.Tenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Memory of user-confirmed treatment per counterparty. Only ever written from a user confirmation. */
@Service
public class RuleService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public RuleService(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    public static String matchKey(String gstin, String name) {
        if (gstin != null && !gstin.isBlank()) return gstin.trim().toUpperCase(Locale.ROOT);
        if (name == null || name.isBlank()) return null;
        return "NAME:" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "")
                .replaceAll("(PVTLTD|PRIVATELIMITED|LIMITED|LTD|INC|LLC|LLP|CORP|CORPORATION|GMBH|BV)$", "");
    }

    public Map<String, Object> find(String key, String direction) {
        if (key == null || direction == null) return null;
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM counterparty_rule WHERE tenant_id=? AND match_key=? AND direction=?", Tenant.current(), key, direction);
        return r.isEmpty() ? null : r.get(0);
    }

    public void upsertFromInvoice(Map<String, Object> inv, String defaultRate, String defaultHsn) {
        String key = matchKey((String) inv.get("counterparty_gstin"), (String) inv.get("counterparty_name"));
        if (key == null) return;
        Map<String, Object> before = find(key, (String) inv.get("direction"));
        jdbc.update("""
                INSERT INTO counterparty_rule(tenant_id, match_key, direction, display_name, category, supply_type, reverse_charge, itc_eligible, default_gst_rate, default_hsn_sac, learned_from_invoice_id, times_applied)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,0)
                ON CONFLICT(tenant_id, match_key, direction) DO UPDATE SET display_name=excluded.display_name, category=excluded.category,
                  supply_type=excluded.supply_type, reverse_charge=excluded.reverse_charge, itc_eligible=excluded.itc_eligible,
                  default_gst_rate=excluded.default_gst_rate, default_hsn_sac=excluded.default_hsn_sac,
                  learned_from_invoice_id=excluded.learned_from_invoice_id, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now')""",
                Tenant.current(), key, inv.get("direction"), inv.get("counterparty_name"), inv.get("category"), inv.get("supply_type"),
                inv.get("reverse_charge"), inv.get("itc_eligible"), defaultRate, defaultHsn, inv.get("id"));
        Map<String, Object> after = find(key, (String) inv.get("direction"));
        audit.record("user", "counterparty_rule", after.get("id"), before == null ? "create" : "update", null, null,
                inv.get("supply_type") + " rcm=" + inv.get("reverse_charge") + " cat=" + inv.get("category"), "learned from invoice #" + inv.get("id"));
    }

    public void markApplied(long ruleId) { jdbc.update("UPDATE counterparty_rule SET times_applied=times_applied+1 WHERE id=?", ruleId); }

    public List<Map<String, Object>> list() { return jdbc.queryForList("SELECT * FROM counterparty_rule WHERE tenant_id=? ORDER BY display_name", Tenant.current()); }

    public void delete(long id) {
        jdbc.update("DELETE FROM counterparty_rule WHERE tenant_id=? AND id=?", Tenant.current(), id);
        audit.record("user", "counterparty_rule", id, "delete", null, null, null, null);
    }
}
