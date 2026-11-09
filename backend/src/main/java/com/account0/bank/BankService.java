package com.account0.bank;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import com.account0.documents.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BankService {
    private final JdbcTemplate jdbc; private final AuditService audit; private final ObjectMapper om;
    public BankService(JdbcTemplate jdbc, AuditService audit, ObjectMapper om) { this.jdbc = jdbc; this.audit = audit; this.om = om; }

    public Map<String, Object> preview(byte[] bytes, String filename) {
        BankParser.Table t = BankParser.read(bytes, filename);
        BankParser.Mapping m = BankParser.detect(t.headers());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headers", t.headers()); out.put("mapping", m);
        out.put("sample", t.rows().stream().limit(5).toList());
        out.put("rowCount", t.rows().size());
        return out;
    }

    public Map<String, Object> importStatement(byte[] bytes, String filename, String account, BankParser.Mapping override) {
        BankParser.Table t = BankParser.read(bytes, filename);
        BankParser.Mapping m = override != null ? override : BankParser.detect(t.headers());
        List<BankParser.Txn> txns = BankParser.parse(t, m);
        String acct = account == null || account.isBlank() ? "Main account" : account.trim();
        try {
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(c -> { PreparedStatement ps = c.prepareStatement("INSERT INTO bank_statement(tenant_id, account_label, filename, mapping_json) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                try { ps.setLong(1, Tenant.current()); ps.setString(2, acct); ps.setString(3, filename); ps.setString(4, om.writeValueAsString(m)); } catch (Exception e) { throw new IllegalStateException(e); } return ps; }, kh);
            long sid = kh.getKey().longValue();
            Map<String, Integer> seen = new HashMap<>(); int fresh = 0, dup = 0;
            for (BankParser.Txn x : txns) {
                String base = acct + "|" + x.date() + "|" + x.description() + "|" + x.debit() + "|" + x.credit() + "|" + x.balance();
                int occ = seen.merge(base, 1, Integer::sum);
                String key = DocumentService.sha256((base + "|" + occ).getBytes());
                int n = jdbc.update("INSERT OR IGNORE INTO bank_txn(tenant_id, statement_id, account_label, txn_date, description, ref, debit_paise, credit_paise, balance_paise, dedupe_key) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        Tenant.current(), sid, acct, x.date(), x.description(), x.ref(), x.debit(), x.credit(), x.balance(), key);
                if (n == 1) fresh++; else dup++;
            }
            jdbc.update("UPDATE bank_statement SET rows_total=?, rows_new=?, rows_duplicate=? WHERE id=?", txns.size(), fresh, dup, sid);
            audit.record("user", "bank_statement", sid, "import", "rows", null, fresh, filename + (dup > 0 ? " (" + dup + " already imported)" : ""));
            Map<String, Object> auto = autoMatch();
            Map<String, Object> out = new LinkedHashMap<>(Map.of("statementId", sid, "rows", txns.size(), "new", fresh, "duplicates", dup));
            out.put("matching", auto); out.put("balanceCheck", balanceCheck(sid));
            return out;
        } catch (ApiException e) { throw e; }
    }

    List<BankMatcher.Invoice> openInvoices() {
        List<BankMatcher.Invoice> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT i.id, i.direction, i.invoice_no, i.invoice_date, i.counterparty_name, i.total_paise,
                  i.total_paise - COALESCE((SELECT SUM(CASE WHEN i.direction='SALE' THEN t.credit_paise ELSE t.debit_paise END) FROM bank_txn t WHERE t.matched_invoice_id=i.id AND t.status='MATCHED'),0) AS outstanding
                FROM invoice i WHERE i.tenant_id=? AND i.status='CONFIRMED' AND i.doc_type='INVOICE' AND i.invoice_date IS NOT NULL ORDER BY i.invoice_date""", Tenant.current()))
            out.add(new BankMatcher.Invoice(((Number) r.get("id")).longValue(), "SALE".equals(r.get("direction")), (String) r.get("invoice_no"), (String) r.get("invoice_date"), (String) r.get("counterparty_name"), ((Number) r.get("outstanding")).longValue()));
        return out;
    }

    /** Auto-matches unambiguous strong candidates; stores the best weaker candidate as SUGGESTED. Never overrides a user's match. */
    public synchronized Map<String, Object> autoMatch() {
        int auto = 0, suggested = 0;
        jdbc.update("UPDATE bank_txn SET status='UNMATCHED', matched_invoice_id=NULL, match_score=NULL, match_note=NULL WHERE tenant_id=? AND status='SUGGESTED'", Tenant.current());
        List<Map<String, Object>> txns = jdbc.queryForList("SELECT * FROM bank_txn WHERE tenant_id=? AND status='UNMATCHED' ORDER BY txn_date, id", Tenant.current());
        for (Map<String, Object> r : txns) {
            List<BankMatcher.Invoice> inv = openInvoices();
            BankMatcher.Txn t = new BankMatcher.Txn(((Number) r.get("id")).longValue(), (String) r.get("txn_date"), (String) r.get("description"), (String) r.get("ref"), ((Number) r.get("debit_paise")).longValue(), ((Number) r.get("credit_paise")).longValue());
            List<BankMatcher.Candidate> ranked = BankMatcher.rank(t, inv);
            if (ranked.isEmpty()) continue;
            BankMatcher.Candidate c = ranked.get(0);
            if (BankMatcher.unambiguousStrong(ranked)) { jdbc.update("UPDATE bank_txn SET status='MATCHED', matched_invoice_id=?, match_source='AUTO', match_score=?, match_note=? WHERE id=?", c.invoiceId(), c.score(), c.note(), t.id()); auto++; }
            else { jdbc.update("UPDATE bank_txn SET status='SUGGESTED', matched_invoice_id=?, match_score=?, match_note=? WHERE id=?", c.invoiceId(), c.score(), c.note(), t.id()); suggested++; }
        }
        return Map.of("autoMatched", auto, "suggested", suggested);
    }

    public Map<String, Object> match(long txnId, long invoiceId) {
        Map<String, Object> t = txn(txnId);
        List<Map<String, Object>> inv = jdbc.queryForList("SELECT id, direction, invoice_no FROM invoice WHERE tenant_id=? AND id=? AND status='CONFIRMED'", Tenant.current(), invoiceId);
        if (inv.isEmpty()) throw ApiException.bad("Invoice must exist and be confirmed.");
        boolean receipt = ((Number) t.get("credit_paise")).longValue() > 0;
        if (receipt != "SALE".equals(inv.get(0).get("direction"))) throw ApiException.bad(receipt ? "A receipt can only be matched to a sales invoice." : "A payment can only be matched to a purchase invoice.");
        jdbc.update("UPDATE bank_txn SET status='MATCHED', matched_invoice_id=?, match_source='USER', match_note=COALESCE(match_note,'matched by you') WHERE tenant_id=? AND id=?", invoiceId, Tenant.current(), txnId);
        audit.record("user", "bank_txn", txnId, "match", "invoice", t.get("matched_invoice_id"), invoiceId, "bank line matched to invoice " + inv.get(0).get("invoice_no"));
        return txn(txnId);
    }
    public Map<String, Object> unmatch(long txnId) {
        Map<String, Object> t = txn(txnId);
        jdbc.update("UPDATE bank_txn SET status='UNMATCHED', matched_invoice_id=NULL, match_source=NULL, match_score=NULL, match_note=NULL, ignore_reason=NULL WHERE tenant_id=? AND id=?", Tenant.current(), txnId);
        audit.record("user", "bank_txn", txnId, "unmatch", "invoice", t.get("matched_invoice_id"), null, "");
        return txn(txnId);
    }
    public Map<String, Object> ignore(long txnId, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.bad("Say why (e.g. bank charges, salary, transfer between own accounts).");
        txn(txnId);
        jdbc.update("UPDATE bank_txn SET status='IGNORED', matched_invoice_id=NULL, ignore_reason=? WHERE tenant_id=? AND id=?", reason, Tenant.current(), txnId);
        audit.record("user", "bank_txn", txnId, "ignore", "reason", null, reason, "");
        return txn(txnId);
    }
    Map<String, Object> txn(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM bank_txn WHERE tenant_id=? AND id=?", Tenant.current(), id);
        if (r.isEmpty()) throw ApiException.notFound("bank transaction " + id);
        return r.get(0);
    }

    public List<Map<String, Object>> txns(String status) {
        String sql = """
                SELECT t.*, i.invoice_no, i.counterparty_name AS invoice_party, i.total_paise AS invoice_total_paise FROM bank_txn t LEFT JOIN invoice i ON i.id=t.matched_invoice_id WHERE t.tenant_id=?""";
        return status == null ? jdbc.queryForList(sql + " ORDER BY t.txn_date, t.id", Tenant.current()) : jdbc.queryForList(sql + " AND t.status=? ORDER BY t.txn_date, t.id", Tenant.current(), status);
    }

    public List<Map<String, Object>> candidates(long txnId) {
        Map<String, Object> r = txn(txnId);
        BankMatcher.Txn t = new BankMatcher.Txn(txnId, (String) r.get("txn_date"), (String) r.get("description"), (String) r.get("ref"), ((Number) r.get("debit_paise")).longValue(), ((Number) r.get("credit_paise")).longValue());
        List<Map<String, Object>> out = new ArrayList<>();
        for (BankMatcher.Invoice i : openInvoices()) {
            if (i.isSale() != (t.credit() > 0) || i.outstandingPaise() <= 0) continue;
            BankMatcher.Candidate c = BankMatcher.score(t, i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("invoiceId", i.id()); m.put("invoiceNo", i.no()); m.put("party", i.party()); m.put("date", i.date()); m.put("outstandingPaise", i.outstandingPaise()); m.put("score", c == null ? 0 : c.score()); m.put("note", c == null ? "" : c.note());
            out.add(m);
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> -((Number) m.get("score")).intValue()));
        return out;
    }

    /** Open (unpaid / part-paid) invoices with age in days. */
    public List<Map<String, Object>> outstanding() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BankMatcher.Invoice i : openInvoices()) {
            Map<String, Object> total = jdbc.queryForMap("SELECT total_paise FROM invoice WHERE id=?", i.id());
            if (i.outstandingPaise() <= 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("invoiceId", i.id()); m.put("direction", i.isSale() ? "SALE" : "PURCHASE"); m.put("invoiceNo", i.no()); m.put("party", i.party()); m.put("date", i.date());
            m.put("totalPaise", total.get("total_paise")); m.put("outstandingPaise", i.outstandingPaise()); m.put("ageDays", ChronoUnit.DAYS.between(LocalDate.parse(i.date()), LocalDate.now()));
            out.add(m);
        }
        return out;
    }

    /** Verifies running balance continuity (previous balance + credit - debit == balance) within a statement. */
    public Map<String, Object> balanceCheck(long statementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, txn_date, debit_paise, credit_paise, balance_paise FROM bank_txn WHERE tenant_id=? AND statement_id=? ORDER BY id", Tenant.current(), statementId);
        List<Map<String, Object>> breaks = new ArrayList<>(); Long prev = null; boolean any = false;
        for (Map<String, Object> r : rows) {
            Object b = r.get("balance_paise"); if (b == null) { prev = null; continue; }
            any = true; long bal = ((Number) b).longValue();
            if (prev != null && prev + ((Number) r.get("credit_paise")).longValue() - ((Number) r.get("debit_paise")).longValue() != bal)
                breaks.add(Map.of("txnId", r.get("id"), "date", r.get("txn_date"), "expectedBalancePaise", prev + ((Number) r.get("credit_paise")).longValue() - ((Number) r.get("debit_paise")).longValue(), "statementBalancePaise", bal));
            prev = bal;
        }
        return Map.of("hasBalances", any, "ok", any && breaks.isEmpty(), "breaks", breaks, "note", !any ? "No balance column — continuity not checked." : breaks.isEmpty() ? "Running balance is consistent: no rows missing." : "Running balance breaks here: a transaction may be missing from the file.");
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT status, COUNT(*) AS n, SUM(debit_paise) AS dr, SUM(credit_paise) AS cr FROM bank_txn WHERE tenant_id=? GROUP BY status", Tenant.current()))
            m.put((String) r.get("status"), Map.of("count", r.get("n"), "debitPaise", r.get("dr"), "creditPaise", r.get("cr")));
        List<Map<String, Object>> st = jdbc.queryForList("SELECT id, account_label, filename, rows_total, rows_new, rows_duplicate, created_at FROM bank_statement WHERE tenant_id=? ORDER BY id DESC", Tenant.current());
        return Map.of("byStatus", m, "statements", st, "outstanding", outstanding());
    }

    public List<Map<String, Object>> alerts() {
        List<Map<String, Object>> out = new ArrayList<>();
        Integer bankRows = jdbc.queryForObject("SELECT COUNT(*) FROM bank_txn WHERE tenant_id=?", Integer.class, Tenant.current());
        if (bankRows == null || bankRows == 0) return out;
        for (Map<String, Object> o : outstanding()) {
            long age = ((Number) o.get("ageDays")).longValue();
            if (age <= 45) continue;
            boolean sale = "SALE".equals(o.get("direction"));
            out.add(com.account0.tds.TdsService.alert(sale ? "RECEIVABLE_OVERDUE" : "PAYABLE_OVERDUE", sale ? "MEDIUM" : "LOW", (String) o.get("date"),
                    (sale ? "Customer " : "Vendor ") + o.get("party") + ": invoice " + o.get("invoiceNo") + " is " + age + " days old with ₹" + String.format(Locale.ROOT, "%,.2f", ((Number) o.get("outstandingPaise")).longValue() / 100.0) + " not found in the bank statements.",
                    sale ? "GST on this invoice is payable to the government even if the customer has not paid you — chase the customer."
                         : "If this vendor is a registered MSME, payment beyond 45 days (15 if no written agreement) makes the expense deductible only when paid (Sec 43B(h) Income-tax Act); and for ITC, payment within 180 days of the invoice date is required (Sec 16(2), 2nd proviso).",
                    "invoice:" + o.get("invoiceId")));
        }
        Integer un = jdbc.queryForObject("SELECT COUNT(*) FROM bank_txn WHERE tenant_id=? AND status IN ('UNMATCHED','SUGGESTED')", Integer.class, Tenant.current());
        if (un != null && un > 0) out.add(com.account0.tds.TdsService.alert("BANK_UNRECONCILED", "LOW", "", un + " bank line(s) are not yet matched to an invoice or marked ignored.", "Unreconciled lines hide missing invoices and unrecorded expenses.", "bank"));
        return out;
    }
}
