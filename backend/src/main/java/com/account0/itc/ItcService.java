package com.account0.itc;

import com.account0.common.ApiException;
import com.account0.common.Money;
import com.account0.common.Tenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ItcService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final com.account0.common.AuditService audit;
    public ItcService(JdbcTemplate jdbc, ObjectMapper om, com.account0.common.AuditService audit) { this.jdbc = jdbc; this.om = om; this.audit = audit; }

    /** Phase 11: the user accepts or rejects a POSSIBLE_MATCH / AMOUNT_MISMATCH 2B line (reason required, audited). Accepted lines are
     *  claimed in 4A(5) at the 2B tax by head; rejected ones stay out. decision = ACCEPTED | REJECTED | CLEAR. */
    public Map<String, Object> decide(long docId, String decision, String reason) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM gstr2b_doc WHERE tenant_id=? AND id=?", Tenant.current(), docId);
        if (r.isEmpty()) throw ApiException.notFound("2B line " + docId);
        Map<String, Object> d = r.get(0);
        match((String) d.get("period"));
        d = jdbc.queryForMap("SELECT * FROM gstr2b_doc WHERE id=?", docId);
        String key = ItcMatcher.norm((String) d.get("doc_no")), ctin = ItcMatcher.normGstin((String) d.get("ctin"));
        List<Map<String, Object>> old = jdbc.queryForList("SELECT * FROM itc_2b_decision WHERE tenant_id=? AND period=? AND ctin=? AND doc_no_norm=? AND doc_type=?", Tenant.current(), d.get("period"), ctin, key, d.get("doc_type"));
        Object before = old.isEmpty() ? null : old.get(0).get("decision");
        if ("CLEAR".equals(decision)) {
            jdbc.update("DELETE FROM itc_2b_decision WHERE tenant_id=? AND period=? AND ctin=? AND doc_no_norm=? AND doc_type=?", Tenant.current(), d.get("period"), ctin, key, d.get("doc_type"));
            audit.record("user", "gstr2b_doc", docId, "decision", "decision", before, null, reason == null ? "cleared" : reason);
            return Map.of("docId", docId, "decision", "NONE");
        }
        if (!"ACCEPTED".equals(decision) && !"REJECTED".equals(decision)) throw ApiException.bad("decision must be ACCEPTED, REJECTED or CLEAR");
        if (reason == null || reason.isBlank()) throw ApiException.bad("Give a reason (it is recorded in the audit trail), e.g. 'vendor confirmed the typo in the invoice number'.");
        String st = (String) d.get("match_status");
        if (!"POSSIBLE_MATCH".equals(st) && !"AMOUNT_MISMATCH".equals(st)) throw ApiException.bad("Only POSSIBLE_MATCH or AMOUNT_MISMATCH lines can be accepted or rejected (this line is " + st + ").");
        if (d.get("matched_invoice_id") == null) throw ApiException.bad("This 2B line has no book invoice paired with it.");
        jdbc.update("""
                INSERT INTO itc_2b_decision(tenant_id, period, ctin, doc_no_norm, doc_type, invoice_id, decision, reason) VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id, period, ctin, doc_no_norm, doc_type) DO UPDATE SET invoice_id=excluded.invoice_id, decision=excluded.decision, reason=excluded.reason,
                  created_at=strftime('%Y-%m-%dT%H:%M:%SZ','now')""", Tenant.current(), d.get("period"), ctin, key, d.get("doc_type"), d.get("matched_invoice_id"), decision, reason.trim());
        audit.record("user", "gstr2b_doc", docId, "decision", "decision", before, decision, st + " " + d.get("doc_no") + " -> invoice " + d.get("matched_invoice_id") + ": " + reason.trim());
        return Map.of("docId", docId, "decision", decision, "invoiceId", d.get("matched_invoice_id"), "reason", reason.trim());
    }

    Map<String, Map<String, Object>> decisionsFor(String period) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM itc_2b_decision WHERE tenant_id=? AND period=?", Tenant.current(), period))
            out.put(r.get("ctin") + "|" + r.get("doc_no_norm") + "|" + r.get("doc_type"), r);
        return out;
    }

    static long p(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() || v.asText().isBlank() ? 0 : Money.toPaise(new BigDecimal(v.asText()));
    }
    static String date(String d) {
        if (d == null || d.isBlank()) return null;
        try { return LocalDate.parse(d, DateTimeFormatter.ofPattern("dd-MM-yyyy")).toString(); } catch (Exception e) { return d; }
    }

    /** Imports a GSTR-2B JSON as downloaded from the portal (b2b, b2ba, cdnr, isd, impg). Replaces any earlier import for the same period. */
    public Map<String, Object> importJson(byte[] bytes, String filename) {
        JsonNode root;
        try { root = om.readTree(bytes); } catch (Exception e) { throw ApiException.bad("Not valid JSON: " + e.getMessage()); }
        JsonNode d = root.has("data") ? root.get("data") : root;
        String rtn = d.path("rtnprd").asText(null);
        if (rtn == null || !rtn.matches("\\d{6}")) throw ApiException.bad("rtnprd (MMYYYY) missing — this does not look like a GSTR-2B JSON.");
        JsonNode dd = d.path("docdata");
        if (dd.isMissingNode()) throw ApiException.bad("docdata missing — this does not look like a GSTR-2B JSON.");
        String period = rtn.substring(2) + "-" + rtn.substring(0, 2);
        jdbc.update("DELETE FROM gstr2b_import WHERE tenant_id=? AND period=?", Tenant.current(), period);
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> { PreparedStatement ps = c.prepareStatement("INSERT INTO gstr2b_import(tenant_id, period, gstin, filename) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, Tenant.current()); ps.setString(2, period); ps.setString(3, d.path("gstin").asText(null)); ps.setString(4, filename); return ps; }, kh);
        long imp = kh.getKey().longValue();
        int n = 0;
        for (JsonNode s : dd.path("b2b")) for (JsonNode inv : s.path("inv")) { insertDoc(imp, period, "B2B", s, inv.path("inum").asText(), inv, "INVOICE"); n++; }
        for (JsonNode s : dd.path("cdnr")) for (JsonNode nt : s.path("nt")) { insertDoc(imp, period, "CDNR", s, nt.path("ntnum").asText(), nt, "C".equals(nt.path("typ").asText()) ? "CREDIT_NOTE" : "DEBIT_NOTE"); n++; }
        // amended invoices: the amended version is what counts (matched like an invoice, on its new number)
        for (JsonNode s : dd.path("b2ba")) for (JsonNode inv : s.path("inv")) { insertDoc(imp, period, "B2BA", s, inv.path("inum").asText(inv.path("oinum").asText()), inv, "INVOICE"); n++; }
        // ISD credit (4A(4)) and import of goods (4A(1)) have no purchase invoice to match
        for (JsonNode s : dd.path("isd")) for (JsonNode doc : s.path("doclist")) {
            com.fasterxml.jackson.databind.node.ObjectNode x = doc.deepCopy();
            if (doc.has("docdt")) x.put("dt", doc.get("docdt").asText());
            if (doc.has("itcelg")) x.put("itcavl", doc.get("itcelg").asText());
            insertDoc(imp, period, "ISD", s, doc.path("docnum").asText(), x, "ISDC".equalsIgnoreCase(doc.path("doctyp").asText()) || "C".equalsIgnoreCase(doc.path("doctyp").asText()) ? "CREDIT_NOTE" : "INVOICE"); n++;
        }
        for (JsonNode boe : dd.path("impg")) {
            com.fasterxml.jackson.databind.node.ObjectNode x = boe.deepCopy(); x.put("dt", boe.path("boedt").asText(null));
            com.fasterxml.jackson.databind.node.ObjectNode sup = om.createObjectNode(); sup.put("ctin", "CUSTOMS:" + boe.path("portcd").asText("")); sup.put("trdnm", "Customs (Bill of Entry)");
            insertDoc(imp, period, "IMPG", sup, boe.path("boenum").asText(), x, "INVOICE"); n++;
        }
        List<Map<String, Object>> res = match(period);
        return Map.of("importId", imp, "period", period, "documents", n, "summary", summary(res));
    }

    private void insertDoc(long imp, String period, String section, JsonNode supplier, String no, JsonNode inv, String type) {
        long tx = p(inv, "txval"), ig = p(inv, "igst"), cg = p(inv, "cgst"), sg = p(inv, "sgst"), ce = p(inv, "cess");
        if (inv.has("items")) { tx = ig = cg = sg = ce = 0; for (JsonNode it : inv.get("items")) { tx += p(it, "txval"); ig += p(it, "igst"); cg += p(it, "cgst"); sg += p(it, "sgst"); ce += p(it, "csamt") + p(it, "cess"); } }
        String dt = date(inv.path("dt").asText(null));
        jdbc.update("""
                INSERT INTO gstr2b_doc(tenant_id, import_id, period, section, ctin, trade_name, doc_no, doc_date, doc_type, supplier_filed_on, supplier_period, reverse_charge, itc_available,
                  taxable_paise, igst_paise, cgst_paise, sgst_paise, cess_paise) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                Tenant.current(), imp, period, section, supplier.path("ctin").asText(), supplier.path("trdnm").asText(null), no, dt, type,
                supplier.path("supfildt").asText(null), supplier.path("supprd").asText(null),
                "Y".equalsIgnoreCase(inv.path("rev").asText("N")) ? 1 : 0, "N".equalsIgnoreCase(inv.path("itcavl").asText("Y")) ? 0 : 1, tx, ig, cg, sg, ce);
    }

    /** Runs the matcher for a period and persists status on each 2B doc. Returns result rows (both sides). */
    public List<Map<String, Object>> match(String period) {
        List<Map<String, Object>> docRows = jdbc.queryForList("SELECT * FROM gstr2b_doc WHERE tenant_id=? AND period=? AND reverse_charge=0 AND section IN ('B2B','B2BA','CDNR')", Tenant.current(), period);
        // books: confirmed non-RCM purchases with a GSTIN (any period — an invoice can show up in a later month's 2B)
        List<Map<String, Object>> bookRows = jdbc.queryForList("""
                SELECT id, invoice_no, counterparty_gstin, counterparty_name, invoice_date, period, igst_paise+cgst_paise+sgst_paise+cess_paise AS tax, taxable_paise, itc_eligible,
                  doc_type, igst_paise, cgst_paise, sgst_paise, cess_paise
                FROM invoice WHERE tenant_id=? AND status='CONFIRMED' AND direction='PURCHASE' AND COALESCE(reverse_charge,0)=0
                  AND counterparty_gstin IS NOT NULL AND counterparty_gstin<>'' AND (period=? OR period<?)""", Tenant.current(), period, period);
        List<ItcMatcher.Doc> docs = docRows.stream().map(r -> new ItcMatcher.Doc(((Number) r.get("id")).longValue(), (String) r.get("ctin"), (String) r.get("doc_no"),
                ((Number) r.get("igst_paise")).longValue() + ((Number) r.get("cgst_paise")).longValue() + ((Number) r.get("sgst_paise")).longValue() + ((Number) r.get("cess_paise")).longValue(),
                (String) r.get("doc_type"), heads(r))).toList();
        // earlier-period invoices only participate if matched to a 2B doc (otherwise they belong to that earlier month's check)
        List<ItcMatcher.Book> books = bookRows.stream().map(r -> new ItcMatcher.Book(((Number) r.get("id")).longValue(), (String) r.get("counterparty_gstin"), (String) r.get("invoice_no"), ((Number) r.get("tax")).longValue(),
                (String) r.get("doc_type"), heads(r))).toList();
        Map<Long, Map<String, Object>> byBook = new HashMap<>(), byDoc = new HashMap<>();
        bookRows.forEach(r -> byBook.put(((Number) r.get("id")).longValue(), r)); docRows.forEach(r -> byDoc.put(((Number) r.get("id")).longValue(), r));
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> dec = decisionsFor(period);
        for (ItcMatcher.Result r : ItcMatcher.match(books, docs)) {
            Map<String, Object> bk = r.bookId() == null ? null : byBook.get(r.bookId());
            if (r.status().equals("IN_BOOKS_NOT_IN_2B") && bk != null && !period.equals(bk.get("period"))) continue; // other months' business
            if (r.docId() != null) jdbc.update("UPDATE gstr2b_doc SET match_status=?, matched_invoice_id=?, match_note=? WHERE id=?", r.status(), r.bookId(), r.note(), r.docId());
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> dc = r.docId() == null ? null : byDoc.get(r.docId());
            row.put("status", r.status()); row.put("note", r.note());
            row.put("invoiceId", r.bookId()); row.put("docId", r.docId());
            row.put("gstin", dc != null ? dc.get("ctin") : bk.get("counterparty_gstin"));
            row.put("party", dc != null && dc.get("trade_name") != null ? dc.get("trade_name") : bk != null ? bk.get("counterparty_name") : null);
            row.put("docNo", dc != null ? dc.get("doc_no") : null); row.put("invoiceNo", bk != null ? bk.get("invoice_no") : null);
            row.put("tax2bPaise", dc == null ? null : docs.stream().filter(x -> x.id() == r.docId()).findFirst().get().taxPaise());
            row.put("taxBooksPaise", bk == null ? null : bk.get("tax"));
            row.put("supplierFiledOn", dc == null ? null : dc.get("supplier_filed_on"));
            row.put("itcAvailable2b", dc == null ? null : dc.get("itc_available"));
            if (dc != null) {
                Map<String, Object> x = dec.get(ItcMatcher.normGstin((String) dc.get("ctin")) + "|" + ItcMatcher.norm((String) dc.get("doc_no")) + "|" + dc.get("doc_type"));
                boolean applies = x != null && r.bookId() != null && ((Number) x.get("invoice_id")).longValue() == r.bookId() && ("POSSIBLE_MATCH".equals(r.status()) || "AMOUNT_MISMATCH".equals(r.status()));
                row.put("decision", applies ? x.get("decision") : null); row.put("decisionReason", applies ? x.get("reason") : null);
                row.put("decidable", "POSSIBLE_MATCH".equals(r.status()) || "AMOUNT_MISMATCH".equals(r.status()));
                row.put("igst2bPaise", dc.get("igst_paise")); row.put("cgst2bPaise", dc.get("cgst_paise")); row.put("sgst2bPaise", dc.get("sgst_paise")); row.put("cess2bPaise", dc.get("cess_paise"));
            }
            out.add(row);
        }
        return out;
    }

    static long[] heads(Map<String, Object> r) {
        return new long[]{((Number) r.get("igst_paise")).longValue(), ((Number) r.get("cgst_paise")).longValue(), ((Number) r.get("sgst_paise")).longValue(), ((Number) r.get("cess_paise")).longValue()};
    }

    static Map<String, Object> summary(List<Map<String, Object>> rows) {
        Map<String, Long> c = new TreeMap<>(); long atRisk = 0;
        for (Map<String, Object> r : rows) { c.merge((String) r.get("status"), 1L, Long::sum); if ("IN_BOOKS_NOT_IN_2B".equals(r.get("status"))) atRisk += ((Number) r.get("taxBooksPaise")).longValue(); }
        return Map.of("counts", c, "itcAtRiskPaise", atRisk);
    }

    public List<Map<String, Object>> imports() {
        return jdbc.queryForList("SELECT i.id, i.period, i.gstin, i.filename, i.created_at, (SELECT COUNT(*) FROM gstr2b_doc d WHERE d.import_id=i.id) AS documents FROM gstr2b_import i WHERE tenant_id=? ORDER BY period DESC", Tenant.current());
    }

    public Map<String, Object> report(String period) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM gstr2b_import WHERE tenant_id=? AND period=?", Integer.class, Tenant.current(), period);
        if (n == null || n == 0) return Map.of("period", period, "imported", false, "rows", List.of(), "summary", Map.of("counts", Map.of(), "itcAtRiskPaise", 0));
        List<Map<String, Object>> rows = match(period);
        return Map.of("period", period, "imported", true, "rows", rows, "summary", summary(rows));
    }
}
