package com.account0.compliance;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import com.account0.gst.GstService;
import com.account0.itc.ItcService;
import com.account0.scrutiny.ScrutinyService;
import com.account0.tds.TdsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.*;

/** Guided month-close checklist. Every item is derived from the data where possible ("auto"); the rest are explicit user ticks. */
@Service
public class CloseService {
    private final JdbcTemplate jdbc; private final GstService gst; private final ItcService itc; private final ScrutinyService scrutiny; private final TdsService tds; private final ComplianceService compliance; private final AuditService audit;
    public CloseService(JdbcTemplate jdbc, GstService gst, ItcService itc, ScrutinyService scrutiny, TdsService tds, ComplianceService compliance, AuditService audit) {
        this.jdbc = jdbc; this.gst = gst; this.itc = itc; this.scrutiny = scrutiny; this.tds = tds; this.compliance = compliance; this.audit = audit;
    }

    private int count(String sql, Object... args) { Integer n = jdbc.queryForObject(sql, Integer.class, args); return n == null ? 0 : n; }
    private Map<String, Object> item(String key, int step, String title, String state, boolean auto, String detail, String link, String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("step", step); m.put("title", title); m.put("state", state); m.put("auto", auto); m.put("detail", detail); m.put("link", link); m.put("why", why);
        return m;
    }

    public Map<String, Object> checklist(String period) {
        if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) throw ApiException.bad("period must be YYYY-MM");
        long t = Tenant.current();
        Map<String, Map<String, Object>> ticks = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM close_tick WHERE tenant_id=? AND period=?", t, period)) ticks.put((String) r.get("item_key"), r);
        List<Map<String, Object>> items = new ArrayList<>();
        int s = 0;

        // 1. documents processed
        int stuck = count("SELECT COUNT(*) FROM document WHERE tenant_id=? AND status IN ('QUEUED','EXTRACTING','FAILED')", t);
        items.add(item("DOCS_PROCESSED", ++s, "All uploaded invoices have been read", stuck == 0 ? "DONE" : "TODO", true,
                stuck == 0 ? "No documents are waiting or failed." : stuck + " document(s) are queued, extracting or failed.", "/inbox",
                "Books are only as complete as the documents in them. Add every sale, purchase and foreign-vendor invoice for the month, including emailed ones."));
        // 2. drafts confirmed
        int drafts = count("SELECT COUNT(*) FROM invoice WHERE tenant_id=? AND status='DRAFT' AND (period=? OR period IS NULL)", t, period);
        int confirmed = count("SELECT COUNT(*) FROM invoice WHERE tenant_id=? AND status='CONFIRMED' AND period=?", t, period);
        items.add(item("INVOICES_CONFIRMED", ++s, "Every invoice for the month is reviewed and confirmed", drafts == 0 && confirmed > 0 ? "DONE" : "TODO", true,
                confirmed + " confirmed, " + drafts + " still in the review queue." + (confirmed == 0 ? " Nothing confirmed for this month yet." : ""), "/review",
                "Only confirmed invoices feed GST, TDS and the ledger. Tax treatment (B2B, export under LUT, RCM...) is always your decision."));
        // 3. duplicates
        int dups = count("SELECT COUNT(*) FROM (SELECT 1 FROM invoice WHERE tenant_id=? AND status<>'REJECTED' AND period=? AND invoice_no IS NOT NULL GROUP BY direction, COALESCE(NULLIF(counterparty_gstin,''), counterparty_name), UPPER(REPLACE(REPLACE(invoice_no,'/',''),'-','')) HAVING COUNT(*)>1)", t, period);
        items.add(item("NO_DUPLICATES", ++s, "No duplicate invoices", dups == 0 ? "DONE" : "TODO", true, dups == 0 ? "No duplicate vendor/invoice-number pairs." : dups + " duplicate group(s).", "/invoices",
                "A duplicate double-counts sales tax or ITC. GST filers have been penalised for ITC claimed twice on the same invoice."));
        // 4. TDS decisions
        long undecided = tds.pending().stream().filter(p -> String.valueOf(p.get("invoice_date")).startsWith(period)).count();
        int purchases = count("SELECT COUNT(*) FROM invoice WHERE tenant_id=? AND status='CONFIRMED' AND direction='PURCHASE' AND period=?", t, period);
        items.add(item("TDS_DECIDED", ++s, "TDS section decided for every purchase", purchases == 0 ? "NA" : undecided == 0 ? "DONE" : "TODO", true,
                purchases == 0 ? "No purchases this month." : undecided == 0 ? "All purchases have a TDS decision." : undecided + " purchase(s) need a TDS decision.", "/tds",
                "TDS must be deducted when the expense is booked or paid, whichever is earlier; missing it disallows 30% of the expense (Sec 40(a)(ia))."));
        // 5. payroll
        int payroll = count("SELECT COUNT(*) FROM payroll_line WHERE tenant_id=? AND month=?", t, period);
        String ptick = state(ticks, "PAYROLL");
        items.add(item("PAYROLL", ++s, "Payroll for the month imported (salary TDS)", payroll > 0 ? "DONE" : ptick != null ? ptick : "TODO", payroll > 0 || ptick == null ? payroll > 0 : false,
                payroll > 0 ? payroll + " payroll line(s) imported." : ptick != null ? "You marked this " + ptick + "." : "No payroll imported. If you have no employees this month, mark it not applicable.", "/payroll",
                "Salary TDS (Sec 192) is deposited by the 7th of the next month and reported in 24Q."));
        // 6. bank
        int bankLines = count("SELECT COUNT(*) FROM bank_txn WHERE tenant_id=? AND substr(txn_date,1,7)=?", t, period);
        int unrec = count("SELECT COUNT(*) FROM bank_txn WHERE tenant_id=? AND substr(txn_date,1,7)=? AND status IN ('UNMATCHED','SUGGESTED')", t, period);
        items.add(item("BANK_RECONCILED", ++s, "Bank statement imported and every line reconciled", bankLines > 0 && unrec == 0 ? "DONE" : "TODO", true,
                bankLines == 0 ? "No bank lines for this month." : unrec == 0 ? bankLines + " lines, all matched or explained." : unrec + " of " + bankLines + " lines still unmatched.", "/bank",
                "Reconciliation finds invoices you forgot to record and payments you made without an invoice."));
        // 7. 2B
        int has2b = count("SELECT COUNT(*) FROM gstr2b_import WHERE tenant_id=? AND period=?", t, period);
        String itcDetail; String itcState;
        if (has2b == 0) { itcState = "TODO"; itcDetail = "GSTR-2B not imported."; }
        else {
            @SuppressWarnings("unchecked") Map<String, Object> sum = (Map<String, Object>) itc.report(period).get("summary");
            @SuppressWarnings("unchecked") Map<String, Number> c = (Map<String, Number>) sum.get("counts");
            int bad = c.getOrDefault("AMOUNT_MISMATCH", 0).intValue() + c.getOrDefault("IN_BOOKS_NOT_IN_2B", 0).intValue() + c.getOrDefault("IN_2B_NOT_IN_BOOKS", 0).intValue() + c.getOrDefault("POSSIBLE_MATCH", 0).intValue();
            itcState = bad == 0 ? "DONE" : "TODO"; itcDetail = bad == 0 ? "GSTR-2B matches your purchase books." : bad + " ITC difference(s) to resolve.";
        }
        items.add(item("ITC_MATCHED", ++s, "GSTR-2B imported and ITC matched", itcState, true, itcDetail, "/itc", "ITC is allowed only for invoices in GSTR-2B (Sec 16(2)(aa)); claim only what matches."));
        // 8. GST recompute warnings
        List<String> warns = gst.warnings(period);
        items.add(item("GST_CLEAN", ++s, "GSTR-3B/GSTR-1 recomputed with no warnings", warns.isEmpty() && confirmed > 0 ? "DONE" : "TODO", true,
                confirmed == 0 ? "No confirmed invoices for the month." : warns.isEmpty() ? "No warnings." : warns.size() + " warning(s): " + String.join("; ", warns.stream().limit(3).toList()), "/gst",
                "Click any number to see the rule and the invoices behind it before you approve the CA's return."));
        // 9. CA pack sent (manual)
        String sent = state(ticks, "CA_PACK_SENT");
        items.add(item("CA_PACK_SENT", ++s, "Month's CA pack sent to your CA", sent != null ? sent : "TODO", false, sent != null ? "Marked " + sent + "." : "Download the CA pack and send it, then tick this.", "/gst",
                "Sending your own recompute lets your CA file from complete data and gives you something to compare against."));
        // 10. filed 3B compared
        List<Map<String, Object>> fr = jdbc.queryForList("SELECT id FROM filed_return WHERE tenant_id=? AND period=? AND confirmed=1 ORDER BY id DESC LIMIT 1", t, period);
        String scrDetail, scrState;
        if (fr.isEmpty()) { scrState = "TODO"; scrDetail = "The CA's filed GSTR-3B has not been uploaded/confirmed."; }
        else try {
            long mism = ((Number) scrutiny.report(((Number) fr.get(0).get("id")).longValue()).get("mismatches")).longValue();
            scrState = mism == 0 ? "DONE" : "TODO"; scrDetail = mism == 0 ? "Filed 3B agrees with your books." : mism + " difference(s) vs your books — see questions for your CA.";
        } catch (Exception e) { scrState = "TODO"; scrDetail = "Could not compare: " + e.getMessage(); }
        items.add(item("FILED_3B_COMPARED", ++s, "Filed GSTR-3B compared with your books", scrState, true, scrDetail, "/scrutiny", "Your CA files on your behalf, but the liability and any penalty are yours."));
        // 11. TDS deposited
        int tdsDue = count("SELECT (SELECT COUNT(*) FROM tds_entry WHERE tenant_id=? AND substr(payment_date,1,7)=? AND tds_paise>0) + (SELECT COUNT(*) FROM payroll_line WHERE tenant_id=? AND month=? AND tds_paise>0)", t, period, t, period);
        int tdsOpen = count("SELECT (SELECT COUNT(*) FROM tds_entry WHERE tenant_id=? AND substr(payment_date,1,7)=? AND tds_paise>0 AND deposited_on IS NULL) + (SELECT COUNT(*) FROM payroll_line WHERE tenant_id=? AND month=? AND tds_paise>0 AND deposited_on IS NULL)", t, period, t, period);
        items.add(item("TDS_DEPOSITED", ++s, "TDS deducted this month is deposited", tdsDue == 0 ? "NA" : tdsOpen == 0 ? "DONE" : "TODO", true,
                tdsDue == 0 ? "No TDS to deposit this month." : tdsOpen == 0 ? "All deposits recorded." : tdsOpen + " deduction(s) without a recorded challan.", "/tds", "Due by the 7th of next month (30 April for March); interest is 1.5% per month."));
        // 12. period locked after filing
        boolean locked = count("SELECT COUNT(*) FROM filing WHERE tenant_id=? AND period=? AND locked=1", t, period) > 0;
        items.add(item("PERIOD_LOCKED", ++s, "Return filing recorded and the period locked", locked ? "DONE" : "TODO", true, locked ? "Filing recorded; invoices dated in this month are locked." : "Record the filing (ARN) to lock the month.", "/filings",
                "Locking stops accidental edits to figures that have already gone to the government."));

        long open = items.stream().filter(i -> "TODO".equals(i.get("state"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period); out.put("items", items); out.put("open", open);
        out.put("readyToClose", open == 0);
        out.put("progress", Map.of("done", items.stream().filter(i -> "DONE".equals(i.get("state")) || "NA".equals(i.get("state"))).count(), "total", items.size()));
        return out;
    }
    private String state(Map<String, Map<String, Object>> ticks, String key) { Map<String, Object> m = ticks.get(key); return m == null ? null : (String) m.get("state"); }

    public Map<String, Object> tick(String period, String key, String state, String note) {
        if (!List.of("PAYROLL", "CA_PACK_SENT").contains(key)) throw ApiException.bad("Item " + key + " is derived from your data and cannot be ticked by hand.");
        if (state == null || state.equals("TODO")) { jdbc.update("DELETE FROM close_tick WHERE tenant_id=? AND period=? AND item_key=?", Tenant.current(), period, key); }
        else {
            if (!state.equals("DONE") && !state.equals("NA")) throw ApiException.bad("state must be DONE, NA or TODO");
            jdbc.update("INSERT INTO close_tick(tenant_id, period, item_key, state, note) VALUES (?,?,?,?,?) ON CONFLICT(tenant_id, period, item_key) DO UPDATE SET state=excluded.state, note=excluded.note, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now')", Tenant.current(), period, key, state, note);
        }
        audit.record("user", "close_tick", period + "|" + key, "tick", "state", null, state, note);
        return checklist(period);
    }
}
