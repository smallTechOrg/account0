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
    private final com.account0.itc.ItcService itc;
    private final ItcClaimService claims;
    private final com.account0.advance.AdvanceService advances;
    public GstService(JdbcTemplate jdbc, CompanyService company, AuditService audit, com.account0.itc.ItcService itc, ItcClaimService claims, com.account0.advance.AdvanceService advances) {
        this.jdbc = jdbc; this.company = company; this.audit = audit; this.itc = itc; this.claims = claims; this.advances = advances;
    }

    static void checkPeriod(String p) { if (p == null || !p.matches("\\d{4}-(0[1-9]|1[0-2])")) throw ApiException.bad("period must be YYYY-MM"); }

    public List<Map<String, Object>> confirmed(String period) {
        checkPeriod(period);
        List<Map<String, Object>> inv = jdbc.queryForList("SELECT * FROM invoice WHERE tenant_id=? AND period=? AND status='CONFIRMED' ORDER BY invoice_date, id", Tenant.current(), period);
        for (Map<String, Object> i : inv) i.put("lines", jdbc.queryForList("SELECT * FROM invoice_line WHERE invoice_id=? ORDER BY line_no", i.get("id")));
        return inv;
    }

    /** RCM time of supply in SQL (Sec 13(3)): earlier of the payment date (entered, or first matched bank payment) and invoice date + 61 days. */
    static final String RCM_TOS_SQL = "CASE WHEN i.direction='PURCHASE' AND COALESCE(i.reverse_charge,0)=1 THEN MIN(COALESCE(i.rcm_paid_on,'9999-12-31'), "
            + "COALESCE((SELECT MIN(t.txn_date) FROM bank_txn t WHERE t.matched_invoice_id=i.id AND t.status='MATCHED' AND t.debit_paise>0),'9999-12-31'), date(i.invoice_date,'+61 days')) END";

    /** Invoices for GSTR-3B of a period: everything by invoice month, except reverse-charge purchases, which go by their time of supply. */
    public List<Map<String, Object>> confirmed3b(String period) {
        checkPeriod(period);
        List<Map<String, Object>> inv = jdbc.queryForList("SELECT * FROM (SELECT i.*, " + RCM_TOS_SQL + " AS rcm_tos_date FROM invoice i WHERE i.tenant_id=? AND i.status='CONFIRMED') "
                + "WHERE (rcm_tos_date IS NULL AND period=?) OR substr(rcm_tos_date,1,7)=? ORDER BY invoice_date, id", Tenant.current(), period, period);
        for (Map<String, Object> i : inv) i.put("lines", jdbc.queryForList("SELECT * FROM invoice_line WHERE invoice_id=? ORDER BY line_no", i.get("id")));
        return inv;
    }

    /** 4A(5)/4A(4) input for a period. A document's ITC is claimed in exactly one period (ItcClaimService.plan): locked GSTR-3B
     *  snapshot first, else the earliest of its provisional (no-2B) period and the 2B periods in which it matched or was accepted. */
    public Gstr3bCalculator.TwoB twoB(String period) { return twoB(period, new ArrayList<>()); }
    /** recOut receives the ITC claim reconciliation rows of the period. */
    public Gstr3bCalculator.TwoB twoB(String period, List<Map<String, Object>> recOut) {
        Map<Long, List<ItcClaimService.Claim>> cands = claims.candidates();
        Map<Long, ItcClaimService.Claim> locked = claims.lockedClaims();
        Map<Long, ItcClaimService.Claim> plan = ItcClaimService.plan(cands, locked);
        recOut.addAll(claims.reconciliation(period, cands, plan));
        boolean imported = claims.twoBPeriods().contains(period);
        List<Map<String, Object>> claim = new ArrayList<>(), isd = new ArrayList<>(); List<String> notes = new ArrayList<>();
        if (claims.filedLocked(period) && locked.values().stream().anyMatch(c -> c.period().equals(period))) {
            for (ItcClaimService.Claim c : locked.values()) if (c.period().equals(period)) claim.add(c.line());
            if (imported) for (Map<String, Object> d : jdbc.queryForList("SELECT * FROM gstr2b_doc WHERE tenant_id=? AND period=? AND section='ISD' AND itc_available=1 ORDER BY id", Tenant.current(), period))
                isd.add(ItcClaimService.lineFrom2b(d, null));
            notes.add("4A(5) for " + period + " is the ITC recorded when its GSTR-3B was filed (period locked): " + claim.size() + " document(s). Later GSTR-2B imports or book changes do not move these claims while the filing stays locked.");
            return new Gstr3bCalculator.TwoB(claim, isd, notes, "FILED");
        }
        if (!imported) {
            for (Map<String, Object> b : claims.bookDocs()) {
                if (!period.equals(b.get("period"))) continue;
                long id = ((Number) b.get("id")).longValue();
                ItcClaimService.Claim c = plan.get(id);
                if (c != null && !c.period().equals(period)) { notes.add("Purchase " + b.get("invoice_no") + " (" + b.get("counterparty_name") + ") is left out of 4A(5): its ITC is already claimed in " + c.period() + " (" + c.basis() + (c.filed() ? ", filed return" : "") + "). A document is claimed once."); continue; }
                claim.add(b);
            }
            return new Gstr3bCalculator.TwoB(claim, isd, notes, "PROVISIONAL");
        }
        List<Map<String, Object>> rows = itc.match(period);   // refreshes match status on every 2B line
        Map<String, Map<String, Object>> dec = claims.decisions();
        for (Map<String, Object> d : jdbc.queryForList("SELECT d.*, i.invoice_date AS book_date, i.itc_eligible FROM gstr2b_doc d LEFT JOIN invoice i ON i.id=d.matched_invoice_id WHERE d.tenant_id=? AND d.period=? ORDER BY d.id", Tenant.current(), period)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.get("matched_invoice_id") != null ? d.get("matched_invoice_id") : "2b:" + d.get("id"));
            m.put("invoice_no", d.get("doc_no")); m.put("invoice_date", d.get("book_date") != null ? d.get("book_date") : d.get("doc_date"));
            m.put("counterparty_name", d.get("trade_name") != null ? d.get("trade_name") : d.get("ctin")); m.put("doc_type", d.get("doc_type"));
            for (String k : List.of("igst_paise", "cgst_paise", "sgst_paise", "cess_paise")) m.put(k, d.get(k));
            m.put("itc_eligible", d.get("itc_eligible"));
            String sec = (String) d.get("section"), label = sec + " " + d.get("doc_no") + " (" + m.get("counterparty_name") + ")";
            if ("IMPG".equals(sec)) { notes.add("2B shows import of goods " + label + ": IGST " + (((Number) d.get("igst_paise")).longValue() / 100.0) + " — 4A(1) is taken from your Bill-of-Entry purchases; compare."); continue; }
            if (((Number) d.get("itc_available")).intValue() == 0) { notes.add("2B line " + label + " is marked 'ITC not available' (itcavl=N) — not claimed."); continue; }
            if ("ISD".equals(sec)) { isd.add(m); continue; }
            if (((Number) d.get("reverse_charge")).intValue() == 1) continue;   // RCM: you pay the tax; credit is 4A(3) from your books
            String basis = ItcClaimService.claimBasis(d, dec);
            if (basis == null) {
                Map<String, Object> x = dec.get(ItcClaimService.decisionKey(d));
                if (x != null && "REJECTED".equals(x.get("decision"))) notes.add("2B line " + label + " was REJECTED by you (" + x.get("reason") + ") — not claimed.");
                else notes.add("2B line " + label + " is " + d.get("match_status") + " — not claimed in 4A(5) until resolved or accepted on the ITC page (" + d.get("match_note") + ").");
                continue;
            }
            ItcClaimService.Claim c = plan.get(((Number) d.get("matched_invoice_id")).longValue());
            if (c != null && !c.period().equals(period)) { notes.add("2B line " + label + " matches a purchase whose ITC is already claimed in " + c.period() + " (" + c.basis() + (c.filed() ? ", filed return" : "") + ") — not claimed again in " + period + "."); continue; }
            if ("2B_ACCEPTED".equals(basis)) notes.add("2B line " + label + " was accepted by you (" + dec.get(ItcClaimService.decisionKey(d)).get("reason") + "): claimed at the 2B tax by head.");
            claim.add(m);
        }
        for (Map<String, Object> r : rows)
            if ("IN_BOOKS_NOT_IN_2B".equals(r.get("status"))) {
                ItcClaimService.Claim c = r.get("invoiceId") == null ? null : plan.get(((Number) r.get("invoiceId")).longValue());
                notes.add("Purchase " + r.get("invoiceNo") + " (" + r.get("party") + ") is not in this period's GSTR-2B — " + (c != null ? "its ITC is claimed in " + c.period() + " (" + c.basis() + ")." : "its ITC is not claimed now; claim it in the month it first appears in 2B (Sec 16(2)(aa))."));
            }
        return new Gstr3bCalculator.TwoB(claim, isd, notes, "2B");
    }

    public List<String> warnings(String period) {
        List<String> w = new ArrayList<>();
        Integer drafts = jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE tenant_id=? AND period=? AND status='DRAFT'", Integer.class, Tenant.current(), period);
        if (drafts != null && drafts > 0) w.add(drafts + " invoice(s) dated in " + period + " are still in the review queue and are NOT included below.");
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE tenant_id=? AND status IN ('QUEUED','EXTRACTING','FAILED')", Integer.class, Tenant.current());
        if (pending != null && pending > 0) w.add(pending + " uploaded document(s) are not yet extracted or failed extraction — they may belong to this period.");
        if (company.gstin() == null) w.add("Company GSTIN is not set — intra/inter-state classification cannot be verified.");
        if ("QUARTERLY".equals(company.get().get("gst_filing_frequency")))
            w.add("Your profile says QUARTERLY (QRMP scheme). Account0 computes monthly figures only: under QRMP, GSTR-1 and GSTR-3B are filed quarterly (B2B invoices of months 1-2 may go via IFF) and tax for months 1-2 is paid by challan (PMT-06). Use these monthly numbers as a working basis and confirm the quarterly figures with your CA.");
        if (isLocked(period)) w.add("This period is LOCKED (filed). Figures are frozen unless you unlock it.");
        return w;
    }

    public Map<String, Object> gstr3b(String period) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("period", period); r.put("gstin", company.gstin());
        r.put("warnings", warnings(period));
        List<Map<String, Object>> rec = new ArrayList<>();
        Gstr3bCalculator.TwoB tb = twoB(period, rec);
        r.putAll(Gstr3bCalculator.compute(confirmed3b(period), Gstin.stateCode(company.gstin()), period, tb, advances.received(period), advances.adjusted(period)));
        r.put("itcClaims", Map.of("rule", "A purchase document's ITC is claimed in exactly one return period: the period recorded by a filed (locked) GSTR-3B, otherwise the earliest of (a) its own period while no GSTR-2B is imported for it (provisional, from books) and (b) each GSTR-2B period in which it matched or you accepted the 2B line. Every other candidate period leaves it out.",
                "ref", "Sec 16(2)(aa), 16(4) CGST Act; Rule 36(4)", "rows", rec));
        @SuppressWarnings("unchecked") List<String> notes = (List<String>) r.get("notes");
        for (Map<String, Object> m : jdbc.queryForList("SELECT * FROM (SELECT i.invoice_no, i.counterparty_name, i.period, " + RCM_TOS_SQL + " AS tos FROM invoice i WHERE i.tenant_id=? AND i.status='CONFIRMED') WHERE period=? AND tos IS NOT NULL AND substr(tos,1,7)<>?", Tenant.current(), period, period))
            notes.add("Reverse-charge invoice " + m.get("invoice_no") + " (" + m.get("counterparty_name") + ") is dated in " + period + " but its time of supply is " + m.get("tos") + " (Sec 13(3): earlier of payment and invoice date + 61 days), so its RCM tax is reported in " + m.get("tos").toString().substring(0, 7) + ". Enter the payment date on the invoice if you have paid.");
        Integer bank = jdbc.queryForObject("SELECT COUNT(*) FROM bank_txn WHERE tenant_id=?", Integer.class, Tenant.current());
        if (bank != null && bank > 0)
            for (Map<String, Object> m : jdbc.queryForList("""
                    SELECT invoice_no, counterparty_name, invoice_date, igst_paise+cgst_paise+sgst_paise+cess_paise AS tax,
                      total_paise - COALESCE((SELECT SUM(t.debit_paise) FROM bank_txn t WHERE t.matched_invoice_id=i.id AND t.status='MATCHED'),0)
                                  - COALESCE((SELECT SUM(e.tds_paise) FROM tds_entry e WHERE e.invoice_id=i.id AND e.status='DEDUCT'),0) AS due
                    FROM invoice i WHERE tenant_id=? AND status='CONFIRMED' AND direction='PURCHASE' AND supply_type='DOMESTIC' AND doc_type='INVOICE' AND itc_eligible=1
                      AND substr(date(invoice_date,'+180 days'),1,7)=?""", Tenant.current(), period))
                if (((Number) m.get("due")).longValue() > 100)
                    notes.add("Rule 37: purchase " + m.get("invoice_no") + " (" + m.get("counterparty_name") + ", " + m.get("invoice_date") + ") reaches 180 days this month and your bank records show it is not fully paid. If unpaid, reverse the ITC (" + (((Number) m.get("tax")).longValue() / 100.0) + ") in 4B(2) with interest, and re-claim it in 4D(1) once paid. Not computed into the figures above — verify with your CA.");
        return r;
    }

    public Map<String, Object> gstr1(String period) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("period", period); r.put("gstin", company.gstin());
        r.put("warnings", warnings(period));
        List<Map<String, Object>> cancelled = jdbc.queryForList("SELECT invoice_no, doc_type FROM invoice WHERE tenant_id=? AND period=? AND status='CANCELLED' AND direction='SALE' ORDER BY invoice_date, id", Tenant.current(), period);
        List<Map<String, Object>> inv = confirmed(period);
        for (Map<String, Object> i : inv)   // credit/debit notes: value of the original invoice decides CDNUR type B2CL vs B2CS netting
            if (!"INVOICE".equals(i.get("doc_type")) && i.get("original_invoice_no") != null) {
                List<Long> t = jdbc.queryForList("SELECT total_paise FROM invoice WHERE tenant_id=? AND direction='SALE' AND doc_type='INVOICE' AND invoice_no=? AND status='CONFIRMED'", Long.class, Tenant.current(), i.get("original_invoice_no"));
                if (!t.isEmpty()) i.put("original_total_paise", t.get(0));
            }
        r.putAll(Gstr1Calculator.computeDocs(inv, company.gstin(), Gstin.stateCode(company.gstin()), period, cancelled));
        Gstr1Calculator.table11(r, advances.received(period), advances.adjusted(period), Gstin.stateCode(company.gstin()));
        return r;
    }

    // ---------- filings / locks ----------
    /** Records which documents' ITC this period's 4A(5) claims (the filed figure becomes authoritative while locked). */
    void snapshotClaims(String period) {
        jdbc.update("DELETE FROM itc_claim WHERE tenant_id=? AND period=?", Tenant.current(), period);   // recompute this period live, then record it
        Gstr3bCalculator.TwoB tb = twoB(period);
        Map<Long, ItcClaimService.Claim> plan = claims.plan();
        int n = claims.snapshot(period, tb.claimable(), tb.provisional() ? "PROVISIONAL" : "2B", plan);
        audit.record("system", "itc_claim", period, "snapshot", "documents", null, n, "4A(5) claims recorded at GSTR-3B filing/lock");
    }

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
        if ("GSTR3B".equals(rt)) snapshotClaims(period);
        return jdbc.queryForMap("SELECT * FROM filing WHERE tenant_id=? AND return_type=? AND period=?", Tenant.current(), rt, period);
    }

    public Map<String, Object> setLock(long id, boolean locked, String reason) {
        Map<String, Object> f = jdbc.queryForMap("SELECT * FROM filing WHERE tenant_id=? AND id=?", Tenant.current(), id);
        if (!locked && (reason == null || reason.isBlank())) throw ApiException.bad("Give a reason to unlock a filed period (it is recorded in the audit trail).");
        jdbc.update("UPDATE filing SET locked=? WHERE id=?", locked ? 1 : 0, id);
        if (locked && "GSTR3B".equals(f.get("return_type"))) snapshotClaims((String) f.get("period"));
        audit.record("user", "filing", f.get("return_type") + ":" + f.get("period"), locked ? "lock" : "unlock", "locked", f.get("locked"), locked ? 1 : 0, reason);
        return jdbc.queryForMap("SELECT * FROM filing WHERE id=?", id);
    }
}
