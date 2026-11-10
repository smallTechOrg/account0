package com.account0.ledger;

import com.account0.common.Tenant;
import com.account0.invoice.SupplyType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/** Deterministic double-entry posting from confirmed invoices. */
@Service
public class LedgerService {
    private final JdbcTemplate jdbc;
    public LedgerService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Line(String account, long debit, long credit) {}

    static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    /** Pure: computes journal lines for an invoice (so it can be unit-tested and explained). */
    public static List<Line> linesFor(Map<String, Object> inv) {
        SupplyType t = SupplyType.valueOf((String) inv.get("supply_type"));
        String party = Objects.toString(inv.get("counterparty_name"), "Unknown party");
        long taxable = l(inv.get("taxable_paise")), cg = l(inv.get("cgst_paise")), sg = l(inv.get("sgst_paise")), ig = l(inv.get("igst_paise")), cess = l(inv.get("cess_paise")), total = l(inv.get("total_paise"));
        boolean itc = l(inv.get("itc_eligible")) == 1;
        String category = (String) inv.get("category");
        List<Line> out = new ArrayList<>();
        if ("SALE".equals(inv.get("direction"))) {
            String income = switch (t) {
                case EXPORT_LUT, EXPORT_PAID -> "Income: Export of services";
                case SEZ_LUT, SEZ_PAID -> "Income: SEZ supplies";
                case NIL_EXEMPT_OUT, NON_GST_OUT -> "Income: Exempt / non-GST";
                default -> "Income: Domestic sales";
            };
            if (category != null && category.startsWith("Income:")) income = category;
            out.add(new Line("Receivable: " + party, total, 0));
            out.add(new Line(income, 0, taxable));
            if (cg > 0) out.add(new Line("Output CGST", 0, cg));
            if (sg > 0) out.add(new Line("Output SGST", 0, sg));
            if (ig > 0) out.add(new Line("Output IGST", 0, ig));
            if (cess > 0) out.add(new Line("Output Cess", 0, cess));
        } else {
            String expense = category != null ? category : "Expense: Uncategorised";
            long tax = cg + sg + ig + cess;
            if (t.isRcm()) {
                out.add(new Line(expense, itc ? taxable : taxable + tax, 0));
                if (itc) addInput(out, cg, sg, ig, cess, " (RCM)");
                out.add(new Line("Payable: " + party, 0, total));
                if (cg > 0) out.add(new Line("RCM CGST payable", 0, cg));
                if (sg > 0) out.add(new Line("RCM SGST payable", 0, sg));
                if (ig > 0) out.add(new Line("RCM IGST payable", 0, ig));
                if (cess > 0) out.add(new Line("RCM Cess payable", 0, cess));
                // total = taxable + cess for RCM; any rounding difference goes to round-off
            } else {
                out.add(new Line(expense, itc ? taxable : taxable + tax, 0));
                if (itc) addInput(out, cg, sg, ig, cess, "");
                out.add(new Line("Payable: " + party, 0, total));
            }
        }
        long dr = out.stream().mapToLong(Line::debit).sum(), cr = out.stream().mapToLong(Line::credit).sum();
        if (dr != cr) out.add(dr > cr ? new Line("Round off", 0, dr - cr) : new Line("Round off", cr - dr, 0));
        if ("CREDIT_NOTE".equals(inv.get("doc_type"))) out = out.stream().map(x -> new Line(x.account(), x.credit(), x.debit())).toList();
        return out;
    }

    private static void addInput(List<Line> out, long cg, long sg, long ig, long cess, String sfx) {
        if (cg > 0) out.add(new Line("Input CGST" + sfx, cg, 0));
        if (sg > 0) out.add(new Line("Input SGST" + sfx, sg, 0));
        if (ig > 0) out.add(new Line("Input IGST" + sfx, ig, 0));
        if (cess > 0) out.add(new Line("Input Cess" + sfx, cess, 0));
    }

    public long postInvoice(Map<String, Object> inv) {
        unpost("invoice", ((Number) inv.get("id")).longValue());
        List<Line> lines = linesFor(inv);
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        String narration = inv.get("direction") + " " + inv.get("doc_type") + " " + inv.get("invoice_no") + " — " + inv.get("counterparty_name");
        jdbc.update(c -> { PreparedStatement ps = c.prepareStatement("INSERT INTO journal(tenant_id, entry_date, period, ref_type, ref_id, narration) VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, Tenant.current()); ps.setString(2, (String) inv.get("invoice_date")); ps.setString(3, (String) inv.get("period"));
            ps.setString(4, "invoice"); ps.setLong(5, ((Number) inv.get("id")).longValue()); ps.setString(6, narration); return ps; }, kh);
        long jid = kh.getKey().longValue();
        for (Line x : lines) jdbc.update("INSERT INTO journal_line(tenant_id, journal_id, account, debit_paise, credit_paise) VALUES (?,?,?,?,?)", Tenant.current(), jid, x.account(), x.debit(), x.credit());
        return jid;
    }

    /**
     * Idempotent generic posting: replaces the journal for (refType, refId) only if its lines differ.
     * Lines must balance (throws otherwise). Returns true if the books changed. Empty/zero lines removes the journal.
     */
    public boolean postGeneric(String refType, long refId, String date, String narration, List<Line> lines) {
        List<Line> ls = lines.stream().filter(x -> x.debit() != 0 || x.credit() != 0).toList();
        long dr = ls.stream().mapToLong(Line::debit).sum(), cr = ls.stream().mapToLong(Line::credit).sum();
        if (dr != cr) throw new IllegalStateException("Unbalanced journal " + refType + ":" + refId + " dr=" + dr + " cr=" + cr);
        List<Map<String, Object>> ex = jdbc.queryForList("SELECT id, entry_date, narration FROM journal WHERE tenant_id=? AND ref_type=? AND ref_id=?", Tenant.current(), refType, refId);
        if (ls.isEmpty()) { if (ex.isEmpty()) return false; unpost(refType, refId); return true; }
        if (ex.size() == 1) {
            List<Map<String, Object>> old = jdbc.queryForList("SELECT account, debit_paise, credit_paise FROM journal_line WHERE journal_id=? ORDER BY id", ex.get(0).get("id"));
            List<Line> oldL = old.stream().map(r -> new Line((String) r.get("account"), l(r.get("debit_paise")), l(r.get("credit_paise")))).toList();
            if (oldL.equals(ls) && date.equals(ex.get(0).get("entry_date")) && Objects.equals(narration, ex.get(0).get("narration"))) return false;
        }
        unpost(refType, refId);
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> { PreparedStatement ps = c.prepareStatement("INSERT INTO journal(tenant_id, entry_date, period, ref_type, ref_id, narration) VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, Tenant.current()); ps.setString(2, date); ps.setString(3, date.substring(0, 7)); ps.setString(4, refType); ps.setLong(5, refId); ps.setString(6, narration); return ps; }, kh);
        long jid = kh.getKey().longValue();
        for (Line x : ls) jdbc.update("INSERT INTO journal_line(tenant_id, journal_id, account, debit_paise, credit_paise) VALUES (?,?,?,?,?)", Tenant.current(), jid, x.account(), x.debit(), x.credit());
        return true;
    }

    public void unpost(String refType, long refId) {
        jdbc.update("DELETE FROM journal_line WHERE journal_id IN (SELECT id FROM journal WHERE tenant_id=? AND ref_type=? AND ref_id=?)", Tenant.current(), refType, refId);
        jdbc.update("DELETE FROM journal WHERE tenant_id=? AND ref_type=? AND ref_id=?", Tenant.current(), refType, refId);
    }

    public List<Map<String, Object>> journal(String period) {
        List<Map<String, Object>> js = period == null
                ? jdbc.queryForList("SELECT * FROM journal WHERE tenant_id=? ORDER BY entry_date, id", Tenant.current())
                : jdbc.queryForList("SELECT * FROM journal WHERE tenant_id=? AND period=? ORDER BY entry_date, id", Tenant.current(), period);
        for (Map<String, Object> j : js) j.put("lines", jdbc.queryForList("SELECT account, debit_paise, credit_paise FROM journal_line WHERE journal_id=? ORDER BY id", j.get("id")));
        return js;
    }

    public Map<String, Object> trialBalance(String period) {
        String where = period == null ? "" : " AND j.period=?";
        Object[] args = period == null ? new Object[]{Tenant.current()} : new Object[]{Tenant.current(), period};
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT l.account, SUM(l.debit_paise) AS debit_paise, SUM(l.credit_paise) AS credit_paise FROM journal_line l JOIN journal j ON j.id=l.journal_id WHERE j.tenant_id=?" + where + " GROUP BY l.account ORDER BY l.account", args);
        long dr = rows.stream().mapToLong(r -> l(r.get("debit_paise"))).sum(), cr = rows.stream().mapToLong(r -> l(r.get("credit_paise"))).sum();
        return Map.of("period", period == null ? "ALL" : period, "accounts", rows, "totalDebitPaise", dr, "totalCreditPaise", cr, "balanced", dr == cr);
    }

    public String journalCsv(String period) {
        StringBuilder sb = new StringBuilder("date,journal_id,narration,account,debit,credit\n");
        for (Map<String, Object> j : journal(period)) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> ls = (List<Map<String, Object>>) j.get("lines");
            for (Map<String, Object> x : ls)
                sb.append(j.get("entry_date")).append(',').append(j.get("id")).append(',').append(csv(j.get("narration"))).append(',').append(csv(x.get("account"))).append(',')
                  .append(java.math.BigDecimal.valueOf(l(x.get("debit_paise")), 2)).append(',').append(java.math.BigDecimal.valueOf(l(x.get("credit_paise")), 2)).append('\n');
        }
        return sb.toString();
    }
    public static String csv(Object o) {
        String s = o == null ? "" : o.toString();
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
