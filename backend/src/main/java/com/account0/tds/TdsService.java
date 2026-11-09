package com.account0.tds;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import com.account0.invoice.RuleService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class TdsService {
    private final JdbcTemplate jdbc; private final AuditService audit; private final RuleService rules;
    public TdsService(JdbcTemplate jdbc, AuditService audit, RuleService rules) { this.jdbc = jdbc; this.audit = audit; this.rules = rules; }

    public static String fyOf(String isoDate) {
        LocalDate d = LocalDate.parse(isoDate);
        int y = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return y + "-" + String.format("%02d", (y + 1) % 100);
    }
    public static int quarterOf(String isoDate) {
        int m = LocalDate.parse(isoDate).getMonthValue();
        return m >= 4 && m <= 6 ? 1 : m <= 9 && m >= 7 ? 2 : m >= 10 ? 3 : 4;
    }
    static String panFromGstin(String g) { return g != null && g.length() >= 12 ? g.substring(2, 12) : null; }

    private boolean nonResident(Map<String, Object> inv) {
        String c = Objects.toString(inv.get("counterparty_country"), "");
        return "IMPORT_SERVICE_RCM".equals(inv.get("supply_type")) || (!c.isBlank() && !c.equalsIgnoreCase("India") && !c.equalsIgnoreCase("IN"));
    }

    /** Confirmed purchase invoices with no TDS decision yet; each shows a suggestion only if a user rule has one. */
    public List<Map<String, Object>> pending() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> inv : jdbc.queryForList("""
                SELECT id, invoice_no, invoice_date, counterparty_name, counterparty_gstin, counterparty_country, supply_type, taxable_paise, category FROM invoice
                WHERE tenant_id=? AND status='CONFIRMED' AND direction='PURCHASE' AND doc_type='INVOICE'
                  AND id NOT IN (SELECT invoice_id FROM tds_entry WHERE tenant_id=?) ORDER BY invoice_date, id""", Tenant.current(), Tenant.current())) {
            Map<String, Object> m = new LinkedHashMap<>(inv);
            boolean nr = nonResident(inv);
            m.put("nonResident", nr);
            if (nr) m.put("note", "Payment to a non-resident: TDS u/s 195 (and Form 15CA/15CB) may apply. Account0 does not compute Sec 195 — ask your CA.");
            else {
                Map<String, Object> rule = rules.find(RuleService.matchKey((String) inv.get("counterparty_gstin"), (String) inv.get("counterparty_name")), "PURCHASE");
                if (rule != null && rule.get("tds_section") != null) { m.put("suggestedSection", rule.get("tds_section")); m.put("suggestionSource", "from your rule"); }
            }
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> assign(long invoiceId, String section, String payeeType, String pan, boolean saveRule) {
        if (!TdsSections.valid(section)) throw ApiException.bad("Unknown TDS section " + section);
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM invoice WHERE tenant_id=? AND id=?", Tenant.current(), invoiceId);
        if (r.isEmpty()) throw ApiException.notFound("invoice " + invoiceId);
        Map<String, Object> inv = r.get(0);
        if (!"CONFIRMED".equals(inv.get("status")) || !"PURCHASE".equals(inv.get("direction"))) throw ApiException.bad("Only confirmed purchase invoices can carry TDS.");
        if (nonResident(inv) && !TdsSections.NONE.equals(section)) throw ApiException.bad("This is a payment to a non-resident — Sec 195, not computed by Account0. Mark it 'Not applicable here' and ask your CA.");
        PayeeTypeOrDefault pt = new PayeeTypeOrDefault(payeeType);
        String panVal = pan != null && !pan.isBlank() ? pan.trim().toUpperCase(Locale.ROOT) : panFromGstin((String) inv.get("counterparty_gstin"));
        if (panVal != null && !panVal.matches("[A-Z]{5}[0-9]{4}[A-Z]")) throw ApiException.bad("PAN format is invalid: " + panVal);
        // 4th letter of PAN: P = individual; H = HUF. Use it unless the user told us otherwise.
        String ptype = payeeType != null && !payeeType.isBlank() ? pt.value : (panVal != null && (panVal.charAt(3) == 'P' || panVal.charAt(3) == 'H') ? "INDIVIDUAL_HUF" : "COMPANY_OR_FIRM");
        String date = (String) inv.get("invoice_date");
        jdbc.update("DELETE FROM tds_entry WHERE tenant_id=? AND invoice_id=?", Tenant.current(), invoiceId);
        jdbc.update("INSERT INTO tds_entry(tenant_id, invoice_id, section, payee_type, payee_pan, payee_name, fy, payment_date, base_paise, status) VALUES (?,?,?,?,?,?,?,?,?,?)",
                Tenant.current(), invoiceId, section, ptype, panVal, inv.get("counterparty_name"), fyOf(date), date, inv.get("taxable_paise"), TdsSections.NONE.equals(section) ? "NOT_APPLICABLE" : "PENDING");
        audit.record("user", "tds_entry", invoiceId, "assign", "section", null, section, "user-confirmed TDS section for invoice " + inv.get("invoice_no"));
        if (saveRule && !nonResident(inv)) {
            String key = RuleService.matchKey((String) inv.get("counterparty_gstin"), (String) inv.get("counterparty_name"));
            int n = jdbc.update("UPDATE counterparty_rule SET tds_section=? WHERE tenant_id=? AND match_key=? AND direction='PURCHASE'", section, Tenant.current(), key);
            if (n == 0) jdbc.update("INSERT INTO counterparty_rule(tenant_id, match_key, direction, display_name, tds_section) VALUES (?,?,?,?,?)", Tenant.current(), key, "PURCHASE", inv.get("counterparty_name"), section);
        }
        recompute();
        return jdbc.queryForMap("SELECT * FROM tds_entry WHERE tenant_id=? AND invoice_id=?", Tenant.current(), invoiceId);
    }

    private record PayeeTypeOrDefault(String value) {
        PayeeTypeOrDefault {
            if (value != null && !value.isBlank() && !value.equals("COMPANY_OR_FIRM") && !value.equals("INDIVIDUAL_HUF")) throw ApiException.bad("payeeType must be COMPANY_OR_FIRM or INDIVIDUAL_HUF");
        }
    }

    public void remove(long invoiceId) {
        jdbc.update("DELETE FROM tds_entry WHERE tenant_id=? AND invoice_id=?", Tenant.current(), invoiceId);
        audit.record("user", "tds_entry", invoiceId, "unassign", "section", null, null, "TDS decision cleared");
        recompute();
    }

    /** Recomputes every entry from scratch, deterministically (payee + section + FY groups, ordered by date). */
    public void recompute() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM tds_entry WHERE tenant_id=? AND section<>'NONE' ORDER BY payment_date, id", Tenant.current());
        Map<String, List<Map<String, Object>>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String payee = r.get("payee_pan") != null ? (String) r.get("payee_pan") : "N:" + Objects.toString(r.get("payee_name"), "").toUpperCase(Locale.ROOT);
            g.computeIfAbsent(payee + "|" + r.get("section") + "|" + r.get("fy"), k -> new ArrayList<>()).add(r);
        }
        for (List<Map<String, Object>> l : g.values()) {
            Map<String, Object> f = l.get(0);
            var res = TdsEngine.compute(TdsSections.ALL.get((String) f.get("section")), TdsSections.PayeeType.valueOf((String) f.get("payee_type")), f.get("payee_pan") != null,
                    l.stream().map(r -> new TdsEngine.Payment(((Number) r.get("id")).longValue(), ((Number) r.get("base_paise")).longValue())).toList());
            for (TdsEngine.Result x : res)
                jdbc.update("UPDATE tds_entry SET rate_bp=?, tds_paise=?, status=?, reason=? WHERE id=?", x.rateBp(), x.tdsPaise(), x.status(), x.reason(), x.id());
        }
    }

    public List<Map<String, Object>> register(String fy, Integer quarter) {
        List<Map<String, Object>> l = jdbc.queryForList("""
                SELECT e.*, i.invoice_no, i.counterparty_gstin FROM tds_entry e JOIN invoice i ON i.id=e.invoice_id
                WHERE e.tenant_id=? AND e.fy=? ORDER BY e.payment_date, e.id""", Tenant.current(), fy);
        if (quarter != null) l = l.stream().filter(r -> quarterOf((String) r.get("payment_date")) == quarter).toList();
        return l;
    }

    public Map<String, Object> deposit(long entryId, String challan, String bsr, String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw ApiException.bad("deposit date YYYY-MM-DD required");
        int n = jdbc.update("UPDATE tds_entry SET deposited_on=?, challan_no=?, bsr_code=? WHERE tenant_id=? AND id=? AND tds_paise>0", date, challan, bsr, Tenant.current(), entryId);
        if (n == 0) throw ApiException.bad("Nothing to deposit for entry " + entryId + " (no TDS due).");
        audit.record("user", "tds_entry", entryId, "deposit", "deposited_on", null, date, "challan " + challan);
        return jdbc.queryForMap("SELECT * FROM tds_entry WHERE id=?", entryId);
    }

    /** Annual position per payee+section (for threshold monitoring). */
    public List<Map<String, Object>> thresholds(String fy) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT COALESCE(payee_pan, 'N:'||payee_name) AS k, MAX(payee_name) AS payee, section, SUM(base_paise) AS total, SUM(tds_paise) AS tds FROM tds_entry
                WHERE tenant_id=? AND fy=? AND section<>'NONE' GROUP BY k, section""", Tenant.current(), fy)) {
            TdsSections.Section s = TdsSections.ALL.get((String) r.get("section"));
            long total = ((Number) r.get("total")).longValue();
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("annualThresholdPaise", s.annualThreshold());
            m.put("percentOfThreshold", Math.round(total * 100.0 / s.annualThreshold()));
            m.put("crossed", total > s.annualThreshold());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> alerts() {
        List<Map<String, Object>> out = new ArrayList<>();
        long t = Tenant.current();
        for (Map<String, Object> p : pending()) {
            boolean nr = Boolean.TRUE.equals(p.get("nonResident"));
            out.add(alert(nr ? "TDS_SEC195" : "TDS_UNDECIDED", nr ? "MEDIUM" : "LOW", (String) p.get("invoice_date"),
                    nr ? "Invoice " + p.get("invoice_no") + " from " + p.get("counterparty_name") + " is a payment to a non-resident — TDS u/s 195 / Form 15CA-CB may apply."
                       : "Invoice " + p.get("invoice_no") + " from " + p.get("counterparty_name") + " has no TDS decision — pick a section or mark 'not applicable'.",
                    nr ? "Account0 does not compute Sec 195 (rate depends on the treaty, nature of payment and TRC). Ask your CA before remitting." : "TDS must be deducted at the time of credit or payment, whichever is earlier; failing to deduct disallows 30% of the expense (Sec 40(a)(ia)).",
                    "invoice:" + p.get("id")));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT DISTINCT fy FROM tds_entry WHERE tenant_id=?", t)) {
            for (Map<String, Object> th : thresholds((String) r.get("fy"))) {
                long pct = ((Number) th.get("percentOfThreshold")).longValue();
                boolean crossed = Boolean.TRUE.equals(th.get("crossed"));
                if (!crossed && pct >= 80)
                    out.add(alert("TDS_THRESHOLD_NEAR", "LOW", null, th.get("payee") + " (" + th.get("section") + ", FY " + r.get("fy") + ") is at " + pct + "% of the annual TDS threshold.", "Once the year's total crosses the threshold, TDS applies to the whole year including earlier payments.", "section:" + th.get("section")));
            }
        }
        for (Map<String, Object> e : jdbc.queryForList("SELECT e.id, e.payee_name, e.tds_paise, e.payment_date, i.invoice_no FROM tds_entry e JOIN invoice i ON i.id=e.invoice_id WHERE e.tenant_id=? AND e.tds_paise>0 AND e.deposited_on IS NULL", t)) {
            LocalDate d = LocalDate.parse((String) e.get("payment_date"));
            LocalDate due = d.getMonthValue() == 3 ? LocalDate.of(d.getYear(), 4, 30) : d.plusMonths(1).withDayOfMonth(7);
            if (LocalDate.now().isAfter(due))
                out.add(alert("TDS_DEPOSIT_OVERDUE", "HIGH", d.toString().substring(0, 7), "TDS ₹" + String.format(Locale.ROOT, "%,.2f", ((Number) e.get("tds_paise")).longValue() / 100.0) + " on " + e.get("invoice_no") + " (" + e.get("payee_name") + ") was due to be deposited by " + due + ".",
                        "Late deposit attracts interest at 1.5% per month from the date of deduction (Sec 201(1A)).", "tds:" + e.get("id")));
        }
        return out;
    }

    public static Map<String, Object> alert(String code, String sev, String period, String msg, String why, Object... refs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code); m.put("severity", sev); m.put("period", period == null ? "" : period.length() > 7 ? period.substring(0, 7) : period); m.put("message", msg); m.put("why", why); m.put("refs", List.of(refs));
        return m;
    }

    /** 26Q-style deductee data for a quarter. */
    public String csv26q(String fy, int quarter) {
        StringBuilder sb = new StringBuilder("Deductee name,PAN,Section,Date of payment/credit,Amount paid (excl. GST),Rate %,TDS deducted,TDS deposited on,Challan no,BSR code,Invoice no\n");
        for (Map<String, Object> r : register(fy, quarter)) {
            if (((Number) r.get("tds_paise")).longValue() == 0 && !"DEDUCT".equals(r.get("status"))) continue;
            sb.append(esc(r.get("payee_name"))).append(',').append(esc(r.get("payee_pan") == null ? "PANNOTAVBL" : r.get("payee_pan"))).append(',').append(r.get("section")).append(',').append(r.get("payment_date")).append(',')
              .append(((Number) r.get("base_paise")).longValue() / 100.0).append(',').append(((Number) r.get("rate_bp")).intValue() / 100.0).append(',').append(((Number) r.get("tds_paise")).longValue() / 100.0).append(',')
              .append(esc(r.get("deposited_on"))).append(',').append(esc(r.get("challan_no"))).append(',').append(esc(r.get("bsr_code"))).append(',').append(esc(r.get("invoice_no"))).append('\n');
        }
        return sb.toString();
    }
    public static String esc(Object o) { String s = o == null ? "" : o.toString(); return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s; }
}
