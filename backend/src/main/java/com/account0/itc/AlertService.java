package com.account0.itc;

import com.account0.common.Tenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** Deterministic alerts over books, 2B import and CA-filed returns. Each alert: code, severity, period, message, why, refs. */
@Service
public class AlertService {
    private final JdbcTemplate jdbc;
    private final ItcService itc;
    private final com.account0.compliance.ComplianceService compliance; private final com.account0.bank.BankService bank; private final com.account0.tds.TdsService tds; private final com.account0.payroll.PayrollService payroll;
    public AlertService(JdbcTemplate jdbc, ItcService itc, com.account0.tds.TdsService tds, com.account0.payroll.PayrollService payroll, com.account0.bank.BankService bank, com.account0.compliance.ComplianceService compliance) { this.compliance = compliance; this.bank = bank; this.jdbc = jdbc; this.itc = itc; this.tds = tds; this.payroll = payroll; }

    static String rs(long p) { return "₹" + String.format(Locale.ROOT, "%,.2f", p / 100.0); }
    private Map<String, Object> a(String code, String sev, String period, String msg, String why, Object... refs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code); m.put("severity", sev); m.put("period", period); m.put("message", msg); m.put("why", why); m.put("refs", List.of(refs));
        return m;
    }

    public List<Map<String, Object>> alerts() {
        long t = Tenant.current();
        List<Map<String, Object>> out = new ArrayList<>();
        // 1. ITC at risk: purchase in books, not in 2B (only for periods with a 2B import); and periods with ITC but no 2B
        Set<String> imported = new HashSet<>(jdbc.queryForList("SELECT period FROM gstr2b_import WHERE tenant_id=?", String.class, t));
        for (String period : imported) {
            for (Map<String, Object> r : itc.match(period)) {
                if ("IN_BOOKS_NOT_IN_2B".equals(r.get("status")))
                    out.add(a("VENDOR_NOT_FILED", "HIGH", period, "Invoice " + r.get("invoiceNo") + " from " + r.get("party") + " (" + r.get("gstin") + ") is not in GSTR-2B for " + period + " — ITC of " + rs(((Number) r.get("taxBooksPaise")).longValue()) + " is at risk.",
                            "ITC is allowed only for invoices that appear in GSTR-2B (Sec 16(2)(aa) CGST Act). Ask the vendor whether they have filed GSTR-1 for the month.", "invoice:" + r.get("invoiceId")));
                else if ("AMOUNT_MISMATCH".equals(r.get("status")))
                    out.add(a("ITC_AMOUNT_MISMATCH", "MEDIUM", period, "Invoice " + r.get("invoiceNo") + " from " + r.get("party") + ": tax in 2B " + rs(((Number) r.get("tax2bPaise")).longValue()) + " vs books " + rs(((Number) r.get("taxBooksPaise")).longValue()) + ".",
                            "Claim ITC only as per GSTR-2B; a difference means a data-entry error on one side.", "invoice:" + r.get("invoiceId")));
                else if ("IN_2B_NOT_IN_BOOKS".equals(r.get("status")))
                    out.add(a("IN_2B_NOT_IN_BOOKS", "MEDIUM", period, "GSTR-2B has " + r.get("docNo") + " from " + r.get("party") + " (" + r.get("gstin") + ") that is not in your books.",
                            "Either an invoice you have not uploaded (ITC unclaimed) or someone else's invoice filed against your GSTIN.", "doc2b:" + r.get("docId")));
                else if ("POSSIBLE_MATCH".equals(r.get("status")))
                    out.add(a("POSSIBLE_MATCH", "LOW", period, r.get("note") + "", "A typo in the invoice number prevents automatic matching.", "invoice:" + r.get("invoiceId")));
            }
            for (Map<String, Object> d : jdbc.queryForList("SELECT id, ctin, trade_name, doc_no FROM gstr2b_doc WHERE tenant_id=? AND period=? AND itc_available=0", t, period))
                out.add(a("ITC_NOT_AVAILABLE_2B", "MEDIUM", period, "GSTR-2B marks " + d.get("doc_no") + " from " + d.get("trade_name") + " as ITC not available.", "2B flags credit that cannot be taken this month (e.g. Sec 16(4) time limit or Sec 17(5)).", "doc2b:" + d.get("id")));
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT period, COUNT(*) AS n, SUM(igst_paise+cgst_paise+sgst_paise+cess_paise) AS tax FROM invoice
                WHERE tenant_id=? AND status='CONFIRMED' AND direction='PURCHASE' AND COALESCE(reverse_charge,0)=0 AND COALESCE(itc_eligible,0)=1 AND igst_paise+cgst_paise+sgst_paise+cess_paise>0 GROUP BY period""", t)) {
            if (!imported.contains(r.get("period")))
                out.add(a("NO_2B", "LOW", (String) r.get("period"), "No GSTR-2B imported for " + r.get("period") + " but you have " + r.get("n") + " purchase invoice(s) with " + rs(((Number) r.get("tax")).longValue()) + " ITC. Import 2B to verify.", "Match your claim to 2B before 3B is filed.", "period:" + r.get("period")));
        }
        // 2. Duplicates (same vendor GSTIN/name + normalised invoice no, not rejected)
        Map<String, List<Map<String, Object>>> g = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, status, direction, invoice_no, counterparty_gstin, counterparty_name, period, total_paise FROM invoice WHERE tenant_id=? AND status<>'REJECTED' AND invoice_no IS NOT NULL", t)) {
            String who = r.get("counterparty_gstin") != null && !r.get("counterparty_gstin").toString().isBlank() ? r.get("counterparty_gstin").toString() : "N:" + r.get("counterparty_name");
            g.computeIfAbsent(r.get("direction") + "|" + who + "|" + ItcMatcher.norm((String) r.get("invoice_no")), k -> new ArrayList<>()).add(r);
        }
        for (List<Map<String, Object>> l : g.values()) if (l.size() > 1) {
            Map<String, Object> f = l.get(0);
            out.add(a("DUPLICATE_INVOICE", "HIGH", (String) f.get("period"), "Invoice " + f.get("invoice_no") + " from " + f.get("counterparty_name") + " exists " + l.size() + " times (ids " + l.stream().map(x -> x.get("id").toString()).reduce((x, y) -> x + ", " + y).get() + ").",
                    "Duplicates double-count tax and ITC. Reject one of them.", l.stream().map(x -> "invoice:" + x.get("id")).toArray()));
        }
        // 3. RCM liability: confirmed RCM invoices vs CA-filed 3.1(d)
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT period, COUNT(*) AS n, SUM(igst_paise+cgst_paise+sgst_paise) AS tax FROM invoice WHERE tenant_id=? AND status='CONFIRMED' AND direction='PURCHASE' AND reverse_charge=1 AND igst_paise+cgst_paise+sgst_paise>0 GROUP BY period""", t)) {
            String period = (String) r.get("period");
            List<Map<String, Object>> fr = jdbc.queryForList("SELECT figures_json FROM filed_return WHERE tenant_id=? AND period=? AND confirmed=1 ORDER BY id DESC LIMIT 1", t, period);
            long due = ((Number) r.get("tax")).longValue();
            if (fr.isEmpty()) {
                Integer filed = jdbc.queryForObject("SELECT COUNT(*) FROM filing WHERE tenant_id=? AND period=? AND return_type='GSTR3B'", Integer.class, t, period);
                out.add(a("RCM_UNPAID", filed != null && filed > 0 ? "HIGH" : "MEDIUM", period, "Reverse-charge tax of " + rs(due) + " (" + r.get("n") + " invoice(s)) must be in 3B 3.1(d) and paid in cash for " + period + ". Upload the filed 3B to confirm it was paid.",
                        "RCM tax cannot be paid from ITC balance (Sec 49(4)); late payment attracts interest u/s 50.", "period:" + period));
            } else {
                try {
                    @SuppressWarnings("unchecked") Map<String, Map<String, Number>> f = new com.fasterxml.jackson.databind.ObjectMapper().readValue(fr.get(0).get("figures_json").toString(), Map.class);
                    Map<String, Number> d31 = f.getOrDefault("3.1d", Map.of());
                    long filedRcm = d31.getOrDefault("igst", 0).longValue() + d31.getOrDefault("cgst", 0).longValue() + d31.getOrDefault("sgst", 0).longValue();
                    if (filedRcm + ScrutinyTol.TOL < due)
                        out.add(a("RCM_UNDERPAID", "HIGH", period, "Filed 3B reports RCM tax " + rs(filedRcm) + " but your books show " + rs(due) + " for " + period + ".", "Short-paid RCM carries interest u/s 50 at 18%.", "period:" + period));
                } catch (Exception ignored) {}
            }
        }
        out.addAll(tds.alerts()); out.addAll(payroll.alerts()); out.addAll(bank.alerts()); out.addAll(compliance.alerts());
        Map<String, Integer> sev = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);
        out.sort(Comparator.comparing((Map<String, Object> m) -> sev.get(m.get("severity"))).thenComparing(m -> String.valueOf(m.get("period"))));
        return out;
    }

    static final class ScrutinyTol { static final long TOL = 100; }
}
