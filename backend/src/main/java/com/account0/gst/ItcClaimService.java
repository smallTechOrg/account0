package com.account0.gst;

import com.account0.common.Tenant;
import com.account0.itc.ItcMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Phase 11: decides, for every forward-charge domestic purchase document, the ONE return period in which its ITC is claimed in 4A(5).
 * Candidates: the document's own period while no GSTR-2B is imported for it (PROVISIONAL, from books), and every 2B period in which a
 * 2B line matched it (2B) or the user accepted a possible/amount-mismatched line (2B_ACCEPTED). The earliest candidate wins, except that a
 * claim snapshotted by a locked GSTR-3B filing (table itc_claim) is authoritative while that filing stays locked.
 */
@Service
public class ItcClaimService {
    private final JdbcTemplate jdbc; private final com.account0.itc.ItcService itc;
    public ItcClaimService(JdbcTemplate jdbc, com.account0.itc.ItcService itc) { this.jdbc = jdbc; this.itc = itc; }

    /** One claim (or claim candidate). line = amounts/labels as fed into 4A(5). */
    public record Claim(long invoiceId, String period, String basis, Long doc2bId, Map<String, Object> line, boolean filed) {}

    static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    public Set<String> twoBPeriods() { return new TreeSet<>(jdbc.queryForList("SELECT period FROM gstr2b_import WHERE tenant_id=?", String.class, Tenant.current())); }

    public boolean filedLocked(String period) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM filing WHERE tenant_id=? AND period=? AND return_type='GSTR3B' AND locked=1", Integer.class, Tenant.current(), period);
        return n != null && n > 0;
    }

    /** Claims snapshotted by a GSTR-3B filing that is still locked. */
    public Map<Long, Claim> lockedClaims() {
        Map<Long, Claim> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT c.* FROM itc_claim c WHERE c.tenant_id=? AND EXISTS (SELECT 1 FROM filing f WHERE f.tenant_id=c.tenant_id AND f.period=c.period AND f.return_type='GSTR3B' AND f.locked=1)
                ORDER BY c.period, c.id""", Tenant.current())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("invoice_id")); m.put("invoice_no", r.get("invoice_no")); m.put("invoice_date", r.get("invoice_date")); m.put("counterparty_name", r.get("counterparty_name"));
            m.put("doc_type", r.get("doc_type")); m.put("itc_eligible", r.get("itc_eligible"));
            for (String k : List.of("igst_paise", "cgst_paise", "sgst_paise", "cess_paise")) m.put(k, r.get(k));
            out.put(l(r.get("invoice_id")), new Claim(l(r.get("invoice_id")), (String) r.get("period"), (String) r.get("basis"), r.get("doc2b_id") == null ? null : l(r.get("doc2b_id")), m, true));
        }
        return out;
    }

    /** User decisions on 2B lines: key period|ctin|normalised doc no|doc type. */
    public Map<String, Map<String, Object>> decisions() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM itc_2b_decision WHERE tenant_id=?", Tenant.current()))
            out.put(r.get("period") + "|" + r.get("ctin") + "|" + r.get("doc_no_norm") + "|" + r.get("doc_type"), r);
        return out;
    }
    public static String decisionKey(Map<String, Object> doc) {
        return doc.get("period") + "|" + ItcMatcher.normGstin((String) doc.get("ctin")) + "|" + ItcMatcher.norm((String) doc.get("doc_no")) + "|" + doc.get("doc_type");
    }

    /** Book rows that can carry 4A(5) credit. */
    List<Map<String, Object>> bookDocs() {
        return jdbc.queryForList("""
                SELECT id, invoice_no, invoice_date, counterparty_name, doc_type, itc_eligible, period, igst_paise, cgst_paise, sgst_paise, cess_paise
                FROM invoice WHERE tenant_id=? AND status='CONFIRMED' AND direction='PURCHASE' AND supply_type='DOMESTIC' AND COALESCE(reverse_charge,0)=0 ORDER BY invoice_date, id""", Tenant.current());
    }

    /** Is a 2B line claimable as such (status MATCHED, or accepted by the user)? Returns the basis, or null. */
    static String claimBasis(Map<String, Object> d, Map<String, Map<String, Object>> decisions) {
        if (d.get("matched_invoice_id") == null || l(d.get("itc_available")) == 0 || l(d.get("reverse_charge")) == 1) return null;
        if (!List.of("B2B", "B2BA", "CDNR").contains(d.get("section"))) return null;
        String st = (String) d.get("match_status");
        if ("MATCHED".equals(st)) return "2B";
        if ("POSSIBLE_MATCH".equals(st) || "AMOUNT_MISMATCH".equals(st)) {
            Map<String, Object> dec = decisions.get(decisionKey(d));
            if (dec != null && "ACCEPTED".equals(dec.get("decision")) && l(dec.get("invoice_id")) == l(d.get("matched_invoice_id"))) return "2B_ACCEPTED";
        }
        return null;
    }

    static Map<String, Object> lineFrom2b(Map<String, Object> d, Map<String, Object> book) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.get("matched_invoice_id"));
        m.put("invoice_no", d.get("doc_no")); m.put("invoice_date", book != null ? book.get("invoice_date") : d.get("doc_date"));
        m.put("counterparty_name", d.get("trade_name") != null ? d.get("trade_name") : d.get("ctin")); m.put("doc_type", d.get("doc_type"));
        for (String k : List.of("igst_paise", "cgst_paise", "sgst_paise", "cess_paise")) m.put(k, d.get(k));   // 2B tax by head
        m.put("itc_eligible", book == null ? 1 : book.get("itc_eligible"));
        return m;
    }

    /** Every candidate claim per document (sorted by period), refreshing the 2B match status of every imported period first. */
    public Map<Long, List<Claim>> candidates() {
        Set<String> imported = twoBPeriods();
        for (String p : imported) itc.match(p);
        Map<String, Map<String, Object>> dec = decisions();
        Map<Long, Map<String, Object>> books = new LinkedHashMap<>();
        for (Map<String, Object> b : bookDocs()) books.put(l(b.get("id")), b);
        Map<Long, List<Claim>> out = new LinkedHashMap<>();
        for (Map<String, Object> b : books.values())
            if (!imported.contains((String) b.get("period")))
                out.computeIfAbsent(l(b.get("id")), k -> new ArrayList<>()).add(new Claim(l(b.get("id")), (String) b.get("period"), "PROVISIONAL", null, b, false));
        for (Map<String, Object> d : jdbc.queryForList("SELECT * FROM gstr2b_doc WHERE tenant_id=? AND matched_invoice_id IS NOT NULL ORDER BY period, id", Tenant.current())) {
            String basis = claimBasis(d, dec);
            if (basis == null) continue;
            Map<String, Object> b = books.get(l(d.get("matched_invoice_id")));
            if (b == null) continue;
            out.computeIfAbsent(l(d.get("matched_invoice_id")), k -> new ArrayList<>()).add(new Claim(l(d.get("matched_invoice_id")), (String) d.get("period"), basis, l(d.get("id")), lineFrom2b(d, b), false));
        }
        for (List<Claim> l : out.values()) l.sort(Comparator.comparing(Claim::period));
        return out;
    }

    /** The claim period of every document: a locked filing's snapshot, else the earliest candidate. */
    public Map<Long, Claim> plan() { return plan(candidates(), lockedClaims()); }

    public static Map<Long, Claim> plan(Map<Long, List<Claim>> candidates, Map<Long, Claim> locked) {
        Map<Long, Claim> out = new LinkedHashMap<>(locked);
        for (var e : candidates.entrySet()) if (!out.containsKey(e.getKey()) && !e.getValue().isEmpty()) out.put(e.getKey(), e.getValue().get(0));
        return out;
    }

    /** Reconciliation rows for one period: every document with a claim or a candidate in it, and where it is (or was) claimed. */
    public List<Map<String, Object>> reconciliation(String period, Map<Long, List<Claim>> cands, Map<Long, Claim> plan) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        cands.forEach((id, l) -> { if (l.stream().anyMatch(c -> c.period().equals(period))) ids.add(id); });
        plan.forEach((id, c) -> { if (c.period().equals(period)) ids.add(id); });
        for (long id : ids) {
            Claim c = plan.get(id);
            Map<String, Object> line = c != null ? c.line() : cands.get(id).get(0).line();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("invoiceId", id); m.put("invoiceNo", line.get("invoice_no")); m.put("party", line.get("counterparty_name")); m.put("docType", line.get("doc_type"));
            m.put("candidates", cands.getOrDefault(id, List.of()).stream().map(x -> x.period() + " (" + x.basis() + ")").toList());
            m.put("claimedIn", c == null ? null : c.period()); m.put("basis", c == null ? null : c.basis()); m.put("filed", c != null && c.filed());
            m.put("taxPaise", l(line.get("igst_paise")) + l(line.get("cgst_paise")) + l(line.get("sgst_paise")) + l(line.get("cess_paise")));
            boolean here = c != null && c.period().equals(period);
            m.put("claimedHere", here);
            m.put("note", here ? (c.filed() ? "Claimed here — recorded when this period's GSTR-3B was filed (locked)." : c.basis().equals("PROVISIONAL") ? "Claimed here provisionally from your books (no GSTR-2B imported for " + period + ")." : "Claimed here from this period's GSTR-2B" + (c.basis().equals("2B_ACCEPTED") ? " (line accepted by you)." : "."))
                    : c == null ? "Not claimed." : "Already claimed in " + c.period() + " (" + c.basis() + (c.filed() ? ", filed" : "") + ") — not claimed again here.");
            out.add(m);
        }
        return out;
    }

    /** Snapshot this period's 4A(5) claims (called when a GSTR-3B filing locks the period). Documents claimed by another locked period are skipped. */
    public int snapshot(String period, List<Map<String, Object>> claimable, String basisDefault, Map<Long, Claim> plan) {
        jdbc.update("DELETE FROM itc_claim WHERE tenant_id=? AND period=?", Tenant.current(), period);
        Map<Long, Claim> locked = lockedClaims();
        int n = 0;
        for (Map<String, Object> ln : claimable) {
            if (!(ln.get("id") instanceof Number)) continue;
            long id = l(ln.get("id"));
            if (locked.containsKey(id) && !locked.get(id).period().equals(period)) continue;
            Claim c = plan.get(id);
            jdbc.update("DELETE FROM itc_claim WHERE tenant_id=? AND invoice_id=?", Tenant.current(), id);
            jdbc.update("""
                    INSERT INTO itc_claim(tenant_id, invoice_id, period, basis, doc2b_id, invoice_no, invoice_date, counterparty_name, doc_type, itc_eligible, igst_paise, cgst_paise, sgst_paise, cess_paise)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", Tenant.current(), id, period, c != null && c.period().equals(period) ? c.basis() : basisDefault, c == null ? null : c.doc2bId(),
                    ln.get("invoice_no"), ln.get("invoice_date"), ln.get("counterparty_name"), ln.get("doc_type"), ln.get("itc_eligible"), l(ln.get("igst_paise")), l(ln.get("cgst_paise")), l(ln.get("sgst_paise")), l(ln.get("cess_paise")));
            n++;
        }
        return n;
    }
}
