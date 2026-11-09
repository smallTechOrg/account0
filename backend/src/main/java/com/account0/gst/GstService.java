package com.account0.gst;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import com.account0.company.CompanyService;
import com.account0.company.Gstin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GstService {
    private final JdbcTemplate jdbc;
    private final CompanyService company;
    private final AuditService audit;
    public GstService(JdbcTemplate jdbc, CompanyService company, AuditService audit) { this.jdbc = jdbc; this.company = company; this.audit = audit; }

    static void checkPeriod(String p) { if (p == null || !p.matches("\\d{4}-(0[1-9]|1[0-2])")) throw ApiException.bad("period must be YYYY-MM"); }

    public List<Map<String, Object>> confirmed(String period) {
        checkPeriod(period);
        List<Map<String, Object>> inv = jdbc.queryForList("SELECT * FROM invoice WHERE tenant_id=? AND period=? AND status='CONFIRMED' ORDER BY invoice_date, id", Tenant.current(), period);
        for (Map<String, Object> i : inv) i.put("lines", jdbc.queryForList("SELECT * FROM invoice_line WHERE invoice_id=? ORDER BY line_no", i.get("id")));
        return inv;
    }

    public List<String> warnings(String period) {
        List<String> w = new ArrayList<>();
        Integer drafts = jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE tenant_id=? AND period=? AND status='DRAFT'", Integer.class, Tenant.current(), period);
        if (drafts != null && drafts > 0) w.add(drafts + " invoice(s) dated in " + period + " are still in the review queue and are NOT included below.");
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE tenant_id=? AND status IN ('QUEUED','EXTRACTING','FAILED')", Integer.class, Tenant.current());
        if (pending != null && pending > 0) w.add(pending + " uploaded document(s) are not yet extracted or failed extraction — they may belong to this period.");
        if (company.gstin() == null) w.add("Company GSTIN is not set — intra/inter-state classification cannot be verified.");
        if (isLocked(period)) w.add("This period is LOCKED (filed). Figures are frozen unless you unlock it.");
        return w;
    }

    public Map<String, Object> gstr3b(String period) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("period", period); r.put("gstin", company.gstin());
        r.put("warnings", warnings(period));
        r.putAll(Gstr3bCalculator.compute(confirmed(period), Gstin.stateCode(company.gstin())));
        return r;
    }

    public Map<String, Object> gstr1(String period) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("period", period); r.put("gstin", company.gstin());
        r.put("warnings", warnings(period));
        r.putAll(Gstr1Calculator.compute(confirmed(period), company.gstin(), Gstin.stateCode(company.gstin()), period));
        return r;
    }

    // ---------- filings / locks ----------
    public boolean isLocked(String period) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM filing WHERE tenant_id=? AND period=? AND locked=1", Integer.class, Tenant.current(), period);
        return n != null && n > 0;
    }
    public List<Map<String, Object>> filings() { return jdbc.queryForList("SELECT * FROM filing WHERE tenant_id=? ORDER BY period DESC, return_type", Tenant.current()); }

    public Map<String, Object> recordFiling(Map<String, Object> b) {
        String rt = (String) b.get("return_type"), period = (String) b.get("period");
        checkPeriod(period);
        if (!List.of("GSTR1", "GSTR3B", "GSTR9", "OTHER").contains(rt)) throw ApiException.bad("return_type must be GSTR1, GSTR3B, GSTR9 or OTHER");
        jdbc.update("""
                INSERT INTO filing(tenant_id, return_type, period, arn, filed_on, filed_by, locked, notes) VALUES (?,?,?,?,?,?,1,?)
                ON CONFLICT(tenant_id, return_type, period) DO UPDATE SET arn=excluded.arn, filed_on=excluded.filed_on, filed_by=excluded.filed_by, locked=1, notes=excluded.notes""",
                Tenant.current(), rt, period, b.get("arn"), b.get("filed_on"), b.getOrDefault("filed_by", "CA"), b.get("notes"));
        audit.record("user", "filing", rt + ":" + period, "filed+lock", "arn", null, b.get("arn"), "period locked");
        return jdbc.queryForMap("SELECT * FROM filing WHERE tenant_id=? AND return_type=? AND period=?", Tenant.current(), rt, period);
    }

    public Map<String, Object> setLock(long id, boolean locked, String reason) {
        Map<String, Object> f = jdbc.queryForMap("SELECT * FROM filing WHERE tenant_id=? AND id=?", Tenant.current(), id);
        if (!locked && (reason == null || reason.isBlank())) throw ApiException.bad("Give a reason to unlock a filed period (it is recorded in the audit trail).");
        jdbc.update("UPDATE filing SET locked=? WHERE id=?", locked ? 1 : 0, id);
        audit.record("user", "filing", f.get("return_type") + ":" + f.get("period"), locked ? "lock" : "unlock", "locked", f.get("locked"), locked ? 1 : 0, reason);
        return jdbc.queryForMap("SELECT * FROM filing WHERE id=?", id);
    }
}
