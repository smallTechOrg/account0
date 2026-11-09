package com.account0.invoice;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import com.account0.company.CompanyService;
import com.account0.company.Gstin;
import com.account0.ledger.LedgerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class InvoiceService {
    public static final List<String> HEADER_FIELDS = List.of("direction", "doc_type", "invoice_no", "invoice_date", "counterparty_name", "counterparty_gstin",
            "counterparty_country", "place_of_supply", "supply_type", "reverse_charge", "itc_eligible", "currency", "foreign_amount", "fx_rate",
            "taxable_paise", "cgst_paise", "sgst_paise", "igst_paise", "cess_paise", "total_paise", "category", "original_invoice_no", "notes");
    static final List<String> LINE_FIELDS = List.of("description", "hsn_sac", "uqc", "quantity", "gst_rate", "taxable_paise", "cgst_paise", "sgst_paise", "igst_paise", "cess_paise");
    static final Set<String> NUMERIC = Set.of("taxable_paise", "cgst_paise", "sgst_paise", "igst_paise", "cess_paise", "total_paise", "reverse_charge", "itc_eligible");
    private static final Set<String> TREATMENT = Set.of("supply_type", "reverse_charge", "itc_eligible");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CompanyService company;
    private final RuleService rules;
    private final LedgerService ledger;
    private final PeriodLockService locks;
    private final ObjectMapper om;

    public InvoiceService(JdbcTemplate jdbc, AuditService audit, CompanyService company, RuleService rules, LedgerService ledger, PeriodLockService locks, ObjectMapper om) {
        this.jdbc = jdbc; this.audit = audit; this.company = company; this.rules = rules; this.ledger = ledger; this.locks = locks; this.om = om;
    }

    // ---------- create ----------
    /** Creates a DRAFT. Tax-treatment fields are only pre-filled from a confirmed counterparty rule. */
    @Transactional
    public long createDraft(Long documentId, Map<String, Object> header, List<Map<String, Object>> lines, List<Flag> extractionFlags, String actor) {
        Map<String, Object> h = clean(header);
        for (String t : TREATMENT) h.remove(t); // never accept treatment from extraction
        List<Flag> flags = new ArrayList<>(extractionFlags);
        Map<String, Object> rule = rules.find(RuleService.matchKey((String) h.get("counterparty_gstin"), (String) h.get("counterparty_name")), (String) h.get("direction"));
        if (rule != null) {
            h.put("supply_type", rule.get("supply_type")); h.put("reverse_charge", rule.get("reverse_charge")); h.put("itc_eligible", rule.get("itc_eligible"));
            if (rule.get("category") != null) { h.put("category", rule.get("category")); h.put("category_suggested", 0); }
            h.put("tax_treatment_source", "RULE");
            flags.add(Flag.info("supply_type", "FROM_RULE", "Tax treatment pre-filled from your saved rule for " + rule.get("display_name") + " (applied " + rule.get("times_applied") + "×). Please still confirm."));
            rules.markApplied(((Number) rule.get("id")).longValue());
            if (rule.get("default_gst_rate") != null) for (Map<String, Object> ln : lines) ln.putIfAbsent("gst_rate", rule.get("default_gst_rate"));
        } else h.put("tax_treatment_source", "NONE");
        h.put("period", period((String) h.get("invoice_date")));
        long id = insert(documentId, h, actor);
        replaceLines(id, lines);
        flags.addAll(dupFlags(id, h));
        jdbc.update("UPDATE invoice SET flags_json=? WHERE id=?", json(flags), id);
        audit.record(actor, "invoice", id, "create", null, null, h.get("invoice_no"), documentId == null ? "manual" : "from document #" + documentId);
        return id;
    }

    private long insert(Long documentId, Map<String, Object> h, String actor) {
        List<String> cols = new ArrayList<>(List.of("tenant_id", "document_id"));
        List<Object> vals = new ArrayList<>(Arrays.asList(Tenant.current(), documentId));
        for (var e : h.entrySet()) { cols.add(e.getKey()); vals.add(e.getValue()); }
        String sql = "INSERT INTO invoice(" + String.join(",", cols) + ") VALUES (" + String.join(",", Collections.nCopies(cols.size(), "?")) + ")";
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> { PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < vals.size(); i++) ps.setObject(i + 1, vals.get(i)); return ps; }, kh);
        return kh.getKey().longValue();
    }

    void replaceLines(long id, List<Map<String, Object>> lines) {
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id=?", id);
        int n = 1;
        for (Map<String, Object> raw : lines) {
            Map<String, Object> ln = new LinkedHashMap<>();
            for (String f : LINE_FIELDS) ln.put(f, raw.get(f));
            jdbc.update("INSERT INTO invoice_line(tenant_id, invoice_id, line_no, description, hsn_sac, uqc, quantity, gst_rate, taxable_paise, cgst_paise, sgst_paise, igst_paise, cess_paise) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Tenant.current(), id, n++, ln.get("description"), ln.get("hsn_sac"), ln.get("uqc"), str(ln.get("quantity")), str(ln.get("gst_rate")),
                    n0(ln.get("taxable_paise")), n0(ln.get("cgst_paise")), n0(ln.get("sgst_paise")), n0(ln.get("igst_paise")), n0(ln.get("cess_paise")));
        }
    }

    // ---------- read ----------
    public Map<String, Object> get(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM invoice WHERE tenant_id=? AND id=?", Tenant.current(), id);
        if (r.isEmpty()) throw ApiException.notFound("invoice " + id);
        Map<String, Object> inv = new LinkedHashMap<>(r.get(0));
        inv.put("lines", lines(id));
        inv.put("flags", parseFlags((String) inv.remove("flags_json")));
        List<Flag> live = liveChecks(inv);
        inv.put("checks", live);
        inv.put("canConfirm", live.stream().noneMatch(f -> f.severity().equals("ERROR")));
        inv.put("locked", locks.isLocked((String) inv.get("period")));
        if (inv.get("document_id") != null) {
            List<Map<String, Object>> ex = jdbc.queryForList("SELECT raw_json, model, created_at FROM extraction WHERE document_id=? ORDER BY id DESC LIMIT 1", inv.get("document_id"));
            if (!ex.isEmpty()) inv.put("extraction", ex.get(0));
        }
        return inv;
    }

    public List<Map<String, Object>> lines(long id) {
        return jdbc.queryForList("SELECT * FROM invoice_line WHERE invoice_id=? ORDER BY line_no", id);
    }

    public List<Map<String, Object>> list(String period, String status, String direction) {
        StringBuilder sql = new StringBuilder("SELECT i.*, d.filename FROM invoice i LEFT JOIN document d ON d.id=i.document_id WHERE i.tenant_id=?");
        List<Object> args = new ArrayList<>(List.of(Tenant.current()));
        if (period != null) { sql.append(" AND i.period=?"); args.add(period); }
        if (status != null) { sql.append(" AND i.status=?"); args.add(status); }
        if (direction != null) { sql.append(" AND i.direction=?"); args.add(direction); }
        sql.append(" ORDER BY i.invoice_date DESC, i.id DESC");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> m : rows) m.put("flags", parseFlags((String) m.remove("flags_json")));
        return rows;
    }

    public List<Flag> liveChecks(Map<String, Object> inv) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> ls = (List<Map<String, Object>>) inv.getOrDefault("lines", lines(((Number) inv.get("id")).longValue()));
        List<Flag> f = new ArrayList<>(InvoiceChecks.structural(inv, ls, company.gstin(), LocalDate.now()));
        f.addAll(InvoiceChecks.forConfirm(inv));
        f.addAll(dupFlags(((Number) inv.get("id")).longValue(), inv));
        return f;
    }

    List<Flag> dupFlags(long id, Map<String, Object> h) {
        if (h.get("invoice_no") == null) return List.of();
        String key = h.get("counterparty_gstin") != null ? "counterparty_gstin" : "counterparty_name";
        Object kv = h.get(key);
        if (kv == null) return List.of();
        List<Long> d = jdbc.queryForList("SELECT id FROM invoice WHERE tenant_id=? AND id<>? AND status<>'REJECTED' AND direction IS ? AND invoice_no=? AND " + key + "=?",
                Long.class, Tenant.current(), id, h.get("direction"), h.get("invoice_no"), kv);
        return d.isEmpty() ? List.of() : List.of(Flag.error("invoice_no", "DUPLICATE", "Same invoice number from the same party already exists: invoice #" + d.get(0) + ". Reject one of them."));
    }

    // ---------- update ----------
    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body, String actor) {
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM invoice WHERE tenant_id=? AND id=?", Tenant.current(), id);
        if ("CONFIRMED".equals(before.get("status"))) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Invoice is confirmed; reopen it to edit.");
        locks.assertOpen((String) before.get("period"));
        Map<String, Object> v = clean(body);
        if (v.containsKey("invoice_date")) { v.put("period", period((String) v.get("invoice_date"))); locks.assertOpen((String) v.get("period")); }
        if (v.keySet().stream().anyMatch(TREATMENT::contains)) v.put("tax_treatment_source", "USER");
        if (v.containsKey("category")) v.put("category_suggested", 0);
        if (!v.isEmpty()) {
            StringBuilder sql = new StringBuilder("UPDATE invoice SET ");
            List<Object> args = new ArrayList<>();
            for (var e : v.entrySet()) { sql.append(e.getKey()).append("=?,"); args.add(e.getValue()); }
            sql.setLength(sql.length() - 1);
            sql.append(" WHERE tenant_id=? AND id=?"); args.add(Tenant.current()); args.add(id);
            jdbc.update(sql.toString(), args.toArray());
            audit.diff(actor, "invoice", id, before, v);
        }
        if (body.get("lines") instanceof List<?> ls) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> lines = (List<Map<String, Object>>) ls;
            String old = json(lines(id));
            replaceLines(id, lines);
            audit.record(actor, "invoice", id, "update", "lines", old, json(lines(id)), null);
        }
        return get(id);
    }

    /** Deterministic RCM liability: Σ line taxable × user-confirmed rate. IGST for imports / inter-state; CGST+SGST for intra-state domestic RCM. */
    @Transactional
    public Map<String, Object> computeRcm(long id) {
        Map<String, Object> inv = get(id);
        String st = (String) inv.get("supply_type");
        if (st == null || !SupplyType.valueOf(st).isRcm()) throw ApiException.bad("Choose a reverse-charge supply type first.");
        String ours = Gstin.stateCode(company.gstin());
        boolean intra = SupplyType.valueOf(st) == SupplyType.DOMESTIC_RCM && ours != null && ours.equals(inv.get("place_of_supply"));
        List<Map<String, Object>> ls = new ArrayList<>();
        long cg = 0, sg = 0, ig = 0, taxable = 0;
        for (Map<String, Object> ln : lines(id)) {
            Map<String, Object> m = new LinkedHashMap<>(ln);
            if (m.get("gst_rate") == null) throw ApiException.bad("Line " + m.get("line_no") + " has no GST rate. Enter the rate you have confirmed applies.");
            long tx = InvoiceChecks.l(m.get("taxable_paise"));
            long tax = new BigDecimal(tx).multiply(new BigDecimal((String) m.get("gst_rate"))).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
            if (intra) { long half = tax / 2; m.put("cgst_paise", half); m.put("sgst_paise", tax - half); m.put("igst_paise", 0); cg += half; sg += tax - half; }
            else { m.put("igst_paise", tax); m.put("cgst_paise", 0); m.put("sgst_paise", 0); ig += tax; }
            taxable += tx;
            ls.add(m);
        }
        if (ls.isEmpty()) throw ApiException.bad("Add at least one line with taxable value and rate.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cgst_paise", cg); body.put("sgst_paise", sg); body.put("igst_paise", ig); body.put("taxable_paise", taxable); body.put("lines", ls);
        return update(id, body, "system:rcm-calc");
    }

    @Transactional
    public Map<String, Object> confirm(long id, boolean saveRule, boolean acknowledgeWarnings) {
        Map<String, Object> inv = get(id);
        if ("CONFIRMED".equals(inv.get("status"))) return inv;
        locks.assertOpen((String) inv.get("period"));
        @SuppressWarnings("unchecked") List<Flag> checks = (List<Flag>) inv.get("checks");
        List<Flag> errors = checks.stream().filter(f -> f.severity().equals("ERROR")).toList();
        if (!errors.isEmpty()) throw ApiException.bad("Cannot confirm: " + String.join(" | ", errors.stream().map(Flag::message).toList()));
        List<Flag> warns = new ArrayList<>(checks.stream().filter(f -> f.severity().equals("WARN")).toList());
        @SuppressWarnings("unchecked") List<Flag> stored = (List<Flag>) inv.get("flags");
        warns.addAll(stored.stream().filter(f -> f.severity().equals("WARN")).toList());
        if (!warns.isEmpty() && !acknowledgeWarnings)
            throw ApiException.bad("Review the " + warns.size() + " highlighted warning(s) and tick 'I have checked these' to confirm.");
        jdbc.update("UPDATE invoice SET status='CONFIRMED', confirmed_at=? WHERE id=?", Instant.now().toString(), id);
        audit.record("user", "invoice", id, "confirm", "status", inv.get("status"), "CONFIRMED", warns.isEmpty() ? null : "acknowledged " + warns.size() + " warning(s)");
        Map<String, Object> after = get(id);
        ledger.postInvoice(after);
        if (inv.get("document_id") != null) jdbc.update("UPDATE document SET status='CONFIRMED' WHERE id=?", inv.get("document_id"));
        if (saveRule) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> ls = (List<Map<String, Object>>) after.get("lines");
            String rate = ls.stream().map(l -> (String) l.get("gst_rate")).filter(Objects::nonNull).findFirst().orElse(null);
            String hsn = ls.stream().map(l -> (String) l.get("hsn_sac")).filter(Objects::nonNull).findFirst().orElse(null);
            rules.upsertFromInvoice(after, rate, hsn);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> reopen(long id) {
        Map<String, Object> inv = get(id);
        locks.assertOpen((String) inv.get("period"));
        if (!"CONFIRMED".equals(inv.get("status"))) return inv;
        ledger.unpost("invoice", id);
        jdbc.update("UPDATE invoice SET status='DRAFT', confirmed_at=NULL WHERE id=?", id);
        audit.record("user", "invoice", id, "reopen", "status", "CONFIRMED", "DRAFT", "ledger entries reversed (deleted)");
        if (inv.get("document_id") != null) jdbc.update("UPDATE document SET status='NEEDS_REVIEW' WHERE id=?", inv.get("document_id"));
        return get(id);
    }

    @Transactional
    public Map<String, Object> reject(long id, String reason) {
        Map<String, Object> inv = get(id);
        locks.assertOpen((String) inv.get("period"));
        if ("CONFIRMED".equals(inv.get("status"))) ledger.unpost("invoice", id);
        jdbc.update("UPDATE invoice SET status='REJECTED' WHERE id=?", id);
        audit.record("user", "invoice", id, "reject", "status", inv.get("status"), "REJECTED", reason);
        if (inv.get("document_id") != null) jdbc.update("UPDATE document SET status='REJECTED' WHERE id=?", inv.get("document_id"));
        return get(id);
    }

    // ---------- helpers ----------
    static String period(String date) { return date != null && date.length() >= 7 && date.matches("\\d{4}-\\d{2}.*") ? date.substring(0, 7) : null; }

    Map<String, Object> clean(Map<String, Object> in) {
        Map<String, Object> v = new LinkedHashMap<>();
        for (String f : HEADER_FIELDS) if (in.containsKey(f)) {
            Object x = in.get(f);
            if (x instanceof String s) { s = s.trim(); x = s.isEmpty() ? null : s; }
            if (x instanceof Boolean b) x = b ? 1 : 0;
            if (NUMERIC.contains(f) && x != null) x = num(x);
            if (f.endsWith("_paise") && x == null) x = 0L;
            if (f.equals("counterparty_gstin") && x != null) x = x.toString().toUpperCase(Locale.ROOT).replaceAll("\\s", "");
            if (f.equals("supply_type") && x != null) {
                try { SupplyType.valueOf(x.toString()); } catch (Exception e) { throw ApiException.bad("unknown supply_type " + x); }
            }
            if (f.equals("direction") && x != null && !List.of("SALE", "PURCHASE").contains(x)) throw ApiException.bad("direction must be SALE or PURCHASE");
            v.put(f, x);
        }
        return v;
    }
    static Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        String s = o.toString().trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }
    static long n0(Object o) { Long v = num(o); return v == null ? 0 : v; }
    static String str(Object o) { return o == null ? null : o.toString(); }
    String json(Object o) { try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
    List<Flag> parseFlags(String s) {
        try { return s == null ? List.of() : om.readValue(s, new TypeReference<List<Flag>>() {}); } catch (Exception e) { return List.of(); }
    }
}
