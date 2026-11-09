package com.account0.compliance;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.SettingsService;
import com.account0.common.Tenant;
import com.account0.tds.TdsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ComplianceService {
    private final JdbcTemplate jdbc; private final SettingsService settings; private final AuditService audit;
    public ComplianceService(JdbcTemplate jdbc, SettingsService settings, AuditService audit) { this.jdbc = jdbc; this.settings = settings; this.audit = audit; }

    public static String currentFy(LocalDate d) { int y = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1; return y + "-" + String.format("%02d", (y + 1) % 100); }

    public boolean hasEmployees() {
        if ("true".equalsIgnoreCase(settings.get("company.has_employees", "false"))) return true;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM payroll_line WHERE tenant_id=?", Integer.class, Tenant.current());
        return n != null && n > 0;
    }
    ComplianceCalendar.Options options() {
        String agm = settings.get("company.agm_date", "");
        LocalDate d = null;
        if (!agm.isBlank()) try { d = LocalDate.parse(agm); } catch (Exception ignored) {}
        return new ComplianceCalendar.Options(d, "true".equalsIgnoreCase(settings.get("company.tax_audit", "false")));
    }

    private boolean filed(String type, String period) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM filing WHERE tenant_id=? AND return_type=? AND period=?", Integer.class, Tenant.current(), type, period);
        return n != null && n > 0;
    }
    private String filingArn(String type, String period) {
        List<String> l = jdbc.queryForList("SELECT arn FROM filing WHERE tenant_id=? AND return_type=? AND period=?", String.class, Tenant.current(), type, period);
        return l.isEmpty() ? null : l.get(0);
    }
    private boolean tdsDeposited(String month) {
        long t = Tenant.current();
        Integer due = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM tds_entry WHERE tenant_id=? AND substr(payment_date,1,7)=? AND tds_paise>0) + (SELECT COUNT(*) FROM payroll_line WHERE tenant_id=? AND month=? AND tds_paise>0)", Integer.class, t, month, t, month);
        if (due == null || due == 0) return false;
        Integer open = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM tds_entry WHERE tenant_id=? AND substr(payment_date,1,7)=? AND tds_paise>0 AND deposited_on IS NULL) + (SELECT COUNT(*) FROM payroll_line WHERE tenant_id=? AND month=? AND tds_paise>0 AND deposited_on IS NULL)", Integer.class, t, month, t, month);
        return open != null && open == 0;
    }

    public Map<String, Object> calendar(String fy, LocalDate asOf) {
        List<ComplianceCalendar.Item> items;
        try { items = ComplianceCalendar.generate(fy, options()); } catch (IllegalArgumentException e) { throw ApiException.bad(e.getMessage()); }
        Map<String, Map<String, Object>> st = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM compliance_status WHERE tenant_id=?", Tenant.current())) st.put(r.get("code") + "|" + r.get("period"), r);
        boolean emp = hasEmployees();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (ComplianceCalendar.Item it : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", it.code()); m.put("title", it.title()); m.put("category", it.category()); m.put("period", it.period()); m.put("due", it.due().toString());
            m.put("form", it.form()); m.put("ref", it.ref()); m.put("why", it.why()); m.put("applicability", it.applicability().name());
            Map<String, Object> s = st.get(it.key());
            String state, source = null, doneOn = null, arn = null, note = null;
            if (s != null) { state = (String) s.get("status"); source = "USER"; doneOn = (String) s.get("done_on"); arn = (String) s.get("ref"); note = (String) s.get("note"); }
            else if (it.code().equals("GSTR1") && filed("GSTR1", it.period()) || it.code().equals("GSTR3B") && filed("GSTR3B", it.period())) { state = "DONE"; source = "FILING_HISTORY"; arn = filingArn(it.code(), it.period()); }
            else if (it.code().equals("TDS_DEPOSIT") && tdsDeposited(it.period())) { state = "DONE"; source = "TDS_REGISTER"; }
            else if (it.applicability() == ComplianceCalendar.Applicability.IF_EMPLOYEES && !emp) { state = "NA"; source = "AUTO"; note = "No employees recorded (import payroll or set company.has_employees)."; }
            else state = null;
            if (state == null) {
                long days = ChronoUnit.DAYS.between(asOf, it.due());
                state = days < 0 ? "OVERDUE" : days <= 7 ? "DUE_SOON" : "UPCOMING";
            }
            m.put("state", state); m.put("source", source); m.put("doneOn", doneOn); m.put("filingRef", arn); m.put("note", note);
            m.put("daysLeft", ChronoUnit.DAYS.between(asOf, it.due()));
            m.put("optional", it.applicability() == ComplianceCalendar.Applicability.IF_APPLICABLE);
            counts.merge(state, 1, Integer::sum);
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fy", fy); out.put("asOf", asOf.toString()); out.put("items", rows); out.put("counts", counts);
        out.put("agmDate", options().agmDate() == null ? null : options().agmDate().toString());
        out.put("assumptions", "Statutory dates for a monthly GST filer; governments often extend dates — verify on the portal. A calendar lists obligations by due date within the FY (so it includes last year's ROC/ITR filings).");
        return out;
    }

    public Map<String, Object> mark(String code, String period, String status, String doneOn, String ref, String note) {
        if (code == null || period == null) throw ApiException.bad("code and period required");
        if (status == null || status.equals("OPEN")) {
            jdbc.update("DELETE FROM compliance_status WHERE tenant_id=? AND code=? AND period=?", Tenant.current(), code, period);
            audit.record("user", "compliance", code + "|" + period, "reopen", "status", null, "OPEN", note);
            return Map.of("code", code, "period", period, "status", "OPEN");
        }
        if (!status.equals("DONE") && !status.equals("NA")) throw ApiException.bad("status must be DONE, NA or OPEN");
        if (status.equals("NA") && (note == null || note.isBlank())) throw ApiException.bad("Say why this does not apply to you.");
        if (doneOn != null && !doneOn.isBlank() && !doneOn.matches("\\d{4}-\\d{2}-\\d{2}")) throw ApiException.bad("done_on must be YYYY-MM-DD");
        jdbc.update("""
                INSERT INTO compliance_status(tenant_id, code, period, status, done_on, ref, note) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id, code, period) DO UPDATE SET status=excluded.status, done_on=excluded.done_on, ref=excluded.ref, note=excluded.note, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now')""",
                Tenant.current(), code, period, status, doneOn, ref, note);
        audit.record("user", "compliance", code + "|" + period, "mark", "status", null, status, ref == null ? note : ref);
        return Map.of("code", code, "period", period, "status", status);
    }

    /** Onboarding helper: "I was already compliant" — marks every ALWAYS-applicable item due before a date as done in one audited step. */
    public Map<String, Object> catchUp(String fy, LocalDate before) {
        int n = 0;
        for (ComplianceCalendar.Item it : ComplianceCalendar.generate(fy, options())) {
            if (!it.due().isBefore(before) || it.applicability() == ComplianceCalendar.Applicability.IF_APPLICABLE) continue;
            Integer ex = jdbc.queryForObject("SELECT COUNT(*) FROM compliance_status WHERE tenant_id=? AND code=? AND period=?", Integer.class, Tenant.current(), it.code(), it.period());
            if (ex != null && ex > 0) continue;
            jdbc.update("INSERT INTO compliance_status(tenant_id, code, period, status, note) VALUES (?,?,?,'DONE',?)", Tenant.current(), it.code(), it.period(), "bulk-marked done: already compliant before " + before);
            n++;
        }
        audit.record("user", "compliance", fy, "catch-up", "before", null, before.toString(), n + " items");
        return Map.of("marked", n);
    }

    public List<Map<String, Object>> alerts() { return alerts(LocalDate.now()); }
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> alerts(LocalDate asOf) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String fy : List.of(currentFy(asOf.minusYears(1)), currentFy(asOf))) {
            if (!seen.add(fy)) continue;
            for (Map<String, Object> it : (List<Map<String, Object>>) calendar(fy, asOf).get("items")) {
                if (Boolean.TRUE.equals(it.get("optional"))) continue;
                String state = (String) it.get("state"); long days = ((Number) it.get("daysLeft")).longValue();
                if (state.equals("OVERDUE") && days >= -45)
                    out.add(TdsService.alert("DEADLINE_OVERDUE", "HIGH", ((String) it.get("due")), it.get("title") + " was due on " + it.get("due") + " (" + (-days) + " days ago) and is not marked done.", it.get("why") + "", "calendar:" + it.get("code") + "|" + it.get("period")));
                else if (state.equals("DUE_SOON"))
                    out.add(TdsService.alert("DEADLINE_SOON", days <= 3 ? "MEDIUM" : "LOW", ((String) it.get("due")), it.get("title") + " is due on " + it.get("due") + " (" + days + " day(s) left).", it.get("why") + "", "calendar:" + it.get("code") + "|" + it.get("period")));
            }
        }
        return out;
    }
}
