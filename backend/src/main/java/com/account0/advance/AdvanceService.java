package com.account0.advance;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import com.account0.company.CompanyService;
import com.account0.company.Gstin;
import com.account0.invoice.PeriodLockService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/**
 * Phase 11 (#3): advances received from customers (GSTR-1 Table 11A) and their adjustment against later invoices (11B).
 * Sec 13(2) CGST Act: for SERVICES the time of supply is the earlier of invoice and receipt of payment, so a domestic advance is taxed
 * when received. Goods: no tax on advances (Notification 66/2017-CT, all taxpayers other than composition). Exports under LUT carry no tax.
 */
@Service
public class AdvanceService {
    public static final Set<String> KINDS = Set.of("DOMESTIC", "EXPORT_LUT", "EXPORT_PAID");
    private final JdbcTemplate jdbc; private final AuditService audit; private final CompanyService company; private final PeriodLockService locks; private final com.account0.ledger.PostingService posting;
    public AdvanceService(JdbcTemplate jdbc, AuditService audit, CompanyService company, PeriodLockService locks, com.account0.ledger.PostingService posting) {
        this.jdbc = jdbc; this.audit = audit; this.company = company; this.locks = locks; this.posting = posting;
    }
    static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    /** Pure: tax contained in a tax-inclusive advance. Returns {taxable, igst, cgst, sgst}. */
    public static long[] split(long gross, String rate, boolean intra, boolean taxable) {
        if (!taxable || gross == 0) return new long[]{gross, 0, 0, 0};
        BigDecimal r = new BigDecimal(rate), g = BigDecimal.valueOf(gross), base = r.add(BigDecimal.valueOf(100));
        if (intra) {
            long half = g.multiply(r).divide(base.multiply(BigDecimal.valueOf(2)), 0, RoundingMode.HALF_UP).longValue();
            return new long[]{gross - 2 * half, 0, half, half};
        }
        long ig = g.multiply(r).divide(base, 0, RoundingMode.HALF_UP).longValue();
        return new long[]{gross - ig, ig, 0, 0};
    }
    public static boolean taxable(String kind, String supplyKind) { return !"EXPORT_LUT".equals(kind) && !"GOODS".equals(supplyKind); }

    @Transactional
    public Map<String, Object> create(Map<String, Object> b) {
        String date = str(b.get("receipt_date")), name = str(b.get("customer_name")), kind = Objects.toString(b.get("kind"), "DOMESTIC"), sk = Objects.toString(b.get("supply_kind"), "SERVICES");
        String rate = Objects.toString(b.get("gst_rate"), "18");
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw ApiException.bad("receipt_date YYYY-MM-DD required");
        if (name == null) throw ApiException.bad("customer_name required");
        if (!KINDS.contains(kind)) throw ApiException.bad("kind must be DOMESTIC, EXPORT_LUT or EXPORT_PAID");
        if (!sk.equals("SERVICES") && !sk.equals("GOODS")) throw ApiException.bad("supply_kind must be SERVICES or GOODS");
        if (!List.of("0", "0.1", "0.25", "1.5", "3", "5", "12", "18", "28", "40").contains(rate)) throw ApiException.bad("gst_rate not a GST slab: " + rate);
        String period = date.substring(0, 7);
        locks.assertOpen(period);
        String pos = str(b.get("place_of_supply"));
        if (kind.startsWith("EXPORT")) pos = "96";
        String ours = Gstin.stateCode(company.gstin());
        if (pos == null) throw ApiException.bad("place_of_supply (state code) required");
        Long txnId = b.get("bank_txn_id") == null ? null : ((Number) b.get("bank_txn_id")).longValue();
        long gross;
        if (txnId != null) {
            List<Map<String, Object>> t = jdbc.queryForList("SELECT * FROM bank_txn WHERE tenant_id=? AND id=?", Tenant.current(), txnId);
            if (t.isEmpty()) throw ApiException.notFound("bank transaction " + txnId);
            if (l(t.get(0).get("credit_paise")) <= 0) throw ApiException.bad("An advance must be linked to a receipt (credit) line.");
            if ("MATCHED".equals(t.get(0).get("status")) || "IGNORED".equals(t.get(0).get("status"))) throw ApiException.bad("That bank line is already matched or ignored — unmatch it first.");
            gross = b.get("gross_paise") == null ? l(t.get(0).get("credit_paise")) : l(b.get("gross_paise"));
            if (gross != l(t.get(0).get("credit_paise"))) throw ApiException.bad("The advance must equal the bank receipt (" + l(t.get(0).get("credit_paise")) / 100.0 + ").");
        } else {
            if (b.get("gross_paise") == null) throw ApiException.bad("gross_paise (amount received) required");
            gross = l(b.get("gross_paise"));
        }
        if (gross <= 0) throw ApiException.bad("Amount received must be positive.");
        boolean intra = ours != null && ours.equals(pos);
        long[] s = split(gross, rate, intra, taxable(kind, sk));
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        String fpos = pos;
        jdbc.update(c -> { PreparedStatement ps = c.prepareStatement("""
                INSERT INTO advance_receipt(tenant_id, receipt_date, period, customer_name, customer_gstin, place_of_supply, kind, supply_kind, gst_rate, gross_paise, taxable_paise, igst_paise, cgst_paise, sgst_paise, bank_txn_id, notes)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS);
            Object[] v = {Tenant.current(), date, period, name, str(b.get("customer_gstin")), fpos, kind, sk, rate, gross, s[0], s[1], s[2], s[3], txnId, str(b.get("notes"))};
            for (int i = 0; i < v.length; i++) ps.setObject(i + 1, v[i]); return ps; }, kh);
        long id = kh.getKey().longValue();
        if (txnId != null) jdbc.update("UPDATE bank_txn SET status='MATCHED', match_kind='ADVANCE', match_ref=?, matched_invoice_id=NULL, match_source='USER', match_note='advance received' WHERE id=?", String.valueOf(id), txnId);
        audit.record("user", "advance_receipt", id, "create", "gross_paise", null, gross, kind + " advance from " + name + (taxable(kind, sk) ? " (taxed at receipt, Sec 13(2))" : " (no tax on this advance)"));
        posting.syncAdvances(); posting.syncBank();
        return get(id);
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM advance_receipt WHERE tenant_id=? AND id=? AND status='ACTIVE'", Tenant.current(), id);
        if (r.isEmpty()) throw ApiException.notFound("advance " + id);
        Map<String, Object> m = new LinkedHashMap<>(r.get(0));
        List<Map<String, Object>> adj = jdbc.queryForList("SELECT a.*, i.invoice_no FROM advance_adjustment a JOIN invoice i ON i.id=a.invoice_id WHERE a.advance_id=? ORDER BY a.id", id);
        long used = adj.stream().mapToLong(a -> l(a.get("gross_paise"))).sum();
        m.put("adjustments", adj); m.put("adjustedPaise", used); m.put("balancePaise", l(m.get("gross_paise")) - used);
        m.put("taxedAtReceipt", taxable((String) m.get("kind"), (String) m.get("supply_kind")));
        return m;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id FROM advance_receipt WHERE tenant_id=? AND status='ACTIVE' ORDER BY receipt_date, id", Tenant.current()).stream().map(r -> get(l(r.get("id")))).toList();
    }

    @Transactional
    public Map<String, Object> delete(long id, String reason) {
        Map<String, Object> a = get(id);
        locks.assertOpen((String) a.get("period"));
        if (!((List<?>) a.get("adjustments")).isEmpty()) throw ApiException.bad("Remove the adjustments against invoices first.");
        jdbc.update("UPDATE advance_receipt SET status='DELETED' WHERE id=?", id);
        if (a.get("bank_txn_id") != null) jdbc.update("UPDATE bank_txn SET status='UNMATCHED', match_kind=NULL, match_ref=NULL, match_source=NULL, match_note=NULL WHERE id=? AND match_kind='ADVANCE'", a.get("bank_txn_id"));
        audit.record("user", "advance_receipt", id, "delete", "status", "ACTIVE", "DELETED", reason);
        posting.syncAdvances(); posting.syncBank();
        return Map.of("deleted", id);
    }

    /** Adjust (part of) an advance against a confirmed sales invoice; the tax is apportioned (the last adjustment takes the remainder). */
    @Transactional
    public Map<String, Object> adjust(long id, long invoiceId, Long grossPaise) {
        Map<String, Object> a = get(id);
        List<Map<String, Object>> inv = jdbc.queryForList("SELECT * FROM invoice WHERE tenant_id=? AND id=? AND status='CONFIRMED' AND direction='SALE' AND doc_type='INVOICE'", Tenant.current(), invoiceId);
        if (inv.isEmpty()) throw ApiException.bad("Adjust an advance only against a confirmed sales invoice.");
        String period = (String) inv.get(0).get("period");
        if (period.compareTo((String) a.get("period")) < 0) throw ApiException.bad("The invoice (" + period + ") is earlier than the advance (" + a.get("period") + ") — that is a receipt against an invoice, not an advance; match it in Bank reconciliation instead.");
        locks.assertOpen(period);
        long bal = l(a.get("balancePaise")), g = grossPaise == null ? Math.min(bal, l(inv.get(0).get("total_paise"))) : grossPaise;
        if (g <= 0 || g > bal) throw ApiException.bad("Adjustment must be between ₹0.01 and the unadjusted balance ₹" + bal / 100.0);
        long[] heads = new long[4]; String[] k = {"taxable_paise", "igst_paise", "cgst_paise", "sgst_paise"};
        long gross = l(a.get("gross_paise"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> prev = (List<Map<String, Object>>) a.get("adjustments");
        for (int i = 0; i < 4; i++) {
            String key = k[i]; long total = l(a.get(key)), done = prev.stream().mapToLong(x -> l(x.get(key))).sum();
            heads[i] = g == bal ? total - done : BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(g)).divide(BigDecimal.valueOf(gross), 0, RoundingMode.HALF_UP).longValue();
        }
        jdbc.update("INSERT INTO advance_adjustment(tenant_id, advance_id, invoice_id, period, gross_paise, taxable_paise, igst_paise, cgst_paise, sgst_paise) VALUES (?,?,?,?,?,?,?,?,?)",
                Tenant.current(), id, invoiceId, period, g, heads[0], heads[1], heads[2], heads[3]);
        audit.record("user", "advance_receipt", id, "adjust", "adjusted_paise", null, g, "against invoice " + inv.get(0).get("invoice_no"));
        posting.syncAdvances();
        return get(id);
    }

    @Transactional
    public Map<String, Object> removeAdjustment(long adjId) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM advance_adjustment WHERE tenant_id=? AND id=?", Tenant.current(), adjId);
        if (r.isEmpty()) throw ApiException.notFound("adjustment " + adjId);
        locks.assertOpen((String) r.get(0).get("period"));
        jdbc.update("DELETE FROM advance_adjustment WHERE id=?", adjId);
        audit.record("user", "advance_receipt", r.get(0).get("advance_id"), "unadjust", "adjusted_paise", r.get(0).get("gross_paise"), null, "adjustment removed");
        posting.syncAdvances();
        return get(l(r.get(0).get("advance_id")));
    }

    /** Table 11A rows of a period: advances received in it, less the part adjusted by invoices of the same period (not reported). Taxed advances only. */
    public List<Map<String, Object>> received(String period) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : list()) {
            if (!period.equals(a.get("period")) || !Boolean.TRUE.equals(a.get("taxedAtReceipt"))) continue;
            Map<String, Object> m = row(a, "ADV " + a.get("id") + " (" + a.get("receipt_date") + ")");
            @SuppressWarnings("unchecked") List<Map<String, Object>> adj = (List<Map<String, Object>>) a.get("adjustments");
            for (Map<String, Object> x : adj) if (period.equals(x.get("period")))
                for (String k : List.of("gross_paise", "taxable_paise", "igst_paise", "cgst_paise", "sgst_paise")) m.put(k, l(m.get(k)) - l(x.get(k)));
            if (l(m.get("gross_paise")) > 0) out.add(m);
        }
        return out;
    }
    /** Table 11B rows of a period: adjustments in it of taxed advances received in an earlier period. */
    public List<Map<String, Object>> adjusted(String period) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : list()) {
            if (!Boolean.TRUE.equals(a.get("taxedAtReceipt")) || ((String) a.get("period")).compareTo(period) >= 0) continue;
            @SuppressWarnings("unchecked") List<Map<String, Object>> adj = (List<Map<String, Object>>) a.get("adjustments");
            for (Map<String, Object> x : adj) if (period.equals(x.get("period"))) {
                Map<String, Object> m = row(a, "ADV " + a.get("id") + " adjusted in " + x.get("invoice_no"));
                for (String k : List.of("gross_paise", "taxable_paise", "igst_paise", "cgst_paise", "sgst_paise")) m.put(k, x.get(k));
                out.add(m);
            }
        }
        return out;
    }
    static Map<String, Object> row(Map<String, Object> a, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "adv:" + a.get("id")); m.put("invoice_no", label); m.put("invoice_date", a.get("receipt_date")); m.put("counterparty_name", a.get("customer_name"));
        m.put("doc_type", "ADVANCE"); m.put("place_of_supply", a.get("place_of_supply")); m.put("gst_rate", a.get("gst_rate"));
        for (String k : List.of("gross_paise", "taxable_paise", "igst_paise", "cgst_paise", "sgst_paise")) m.put(k, a.get(k));
        m.put("cess_paise", 0L);
        return m;
    }
    static String str(Object o) { if (o == null) return null; String s = o.toString().trim(); return s.isEmpty() ? null : s; }
}
