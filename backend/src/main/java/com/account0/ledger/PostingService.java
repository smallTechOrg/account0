package com.account0.ledger;

import com.account0.common.AuditService;
import com.account0.common.Tenant;
import com.account0.ledger.LedgerService.Line;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.*;

/**
 * Phase 9: deterministic double-entry journals for bank payments/receipts, TDS (deduct + deposit) and payroll.
 * Every sync is idempotent (a journal is rewritten only when its content changes) and audited when it does.
 * Assumed: unmatched/ignored bank lines are NOT posted (their other side is unknown); challan deposits are paid
 * from "Bank / cash (challan payments)" because challans are not tied to an imported bank line.
 */
@Service
public class PostingService {
    public static final String CHALLAN_BANK = "Bank / cash (challan payments)";
    public static final String TDS_RECEIVABLE = "TDS receivable (deducted by customers)";
    public static final String SALARY_EXPENSE = "Expense: Salaries";
    public static final String SALARY_PAYABLE = "Salary payable";
    public static final String TDS_SALARY = "TDS payable: 192 Salary";

    private final JdbcTemplate jdbc; private final LedgerService ledger; private final AuditService audit;
    public PostingService(JdbcTemplate jdbc, LedgerService ledger, AuditService audit) { this.jdbc = jdbc; this.ledger = ledger; this.audit = audit; }

    static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    static String party(Object name) { return Objects.toString(name, "Unknown party"); }

    // ---------- pure line builders (unit-tested) ----------

    /** Receipt from a customer: bank gets what arrived; the customer-withheld TDS is a receivable from the tax department. */
    public static List<Line> receiptLines(String bankAccount, String customer, long received, long customerTds) {
        List<Line> out = new ArrayList<>();
        out.add(new Line("Bank: " + bankAccount, received, 0));
        if (customerTds > 0) out.add(new Line(TDS_RECEIVABLE, customerTds, 0));
        out.add(new Line("Receivable: " + party(customer), 0, received + customerTds));
        return out;
    }
    /** Payment to a vendor: clears the payable by the cash paid (any TDS was moved out of the payable when deducted). */
    public static List<Line> paymentLines(String bankAccount, String vendor, long paid) {
        return List.of(new Line("Payable: " + party(vendor), paid, 0), new Line("Bank: " + bankAccount, 0, paid));
    }
    public static List<Line> tdsDeductLines(String vendor, String section, long tds) {
        return List.of(new Line("Payable: " + party(vendor), tds, 0), new Line("TDS payable: " + section, 0, tds));
    }
    public static List<Line> tdsDepositLines(String account, long tds) {
        return List.of(new Line(account, tds, 0), new Line(CHALLAN_BANK, 0, tds));
    }
    public static List<Line> payrollLines(long gross, long tds) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(SALARY_EXPENSE, gross, 0));
        if (tds > 0) out.add(new Line(TDS_SALARY, 0, tds));
        out.add(new Line(SALARY_PAYABLE, 0, gross - tds));
        return out;
    }

    // ---------- sync (DB) ----------

    private void audited(boolean changed, String refType, long refId, String note) {
        if (changed) audit.record("system", "journal", refType + ":" + refId, "post", "journal", null, note, "deterministic posting");
    }

    /** Posts every MATCHED bank line; removes journals of lines no longer matched. */
    public void syncBank() {
        Set<Long> keep = new HashSet<>();
        for (Map<String, Object> t : jdbc.queryForList("""
                SELECT t.id, t.txn_date, t.account_label, t.debit_paise, t.credit_paise, t.customer_tds_paise, i.direction, i.counterparty_name, i.invoice_no
                FROM bank_txn t JOIN invoice i ON i.id=t.matched_invoice_id WHERE t.tenant_id=? AND t.status='MATCHED' AND i.status='CONFIRMED' ORDER BY t.id""", Tenant.current())) {
            long id = l(t.get("id"));
            boolean sale = "SALE".equals(t.get("direction"));
            List<Line> lines = sale
                    ? receiptLines((String) t.get("account_label"), (String) t.get("counterparty_name"), l(t.get("credit_paise")), l(t.get("customer_tds_paise")))
                    : paymentLines((String) t.get("account_label"), (String) t.get("counterparty_name"), l(t.get("debit_paise")));
            keep.add(id);
            String narr = (sale ? "Receipt " : "Payment ") + t.get("invoice_no") + " — " + party(t.get("counterparty_name")) + (l(t.get("customer_tds_paise")) > 0 ? " (net of customer TDS)" : "");
            audited(ledger.postGeneric("bank_txn", id, (String) t.get("txn_date"), narr, lines), "bank_txn", id, narr);
        }
        for (long id : staleIds("bank_txn", keep)) { ledger.unpost("bank_txn", id); audit.record("system", "journal", "bank_txn:" + id, "unpost", "journal", null, null, "bank line no longer matched"); }
    }

    /** Posts TDS deduction (Dr vendor payable / Cr TDS payable) and, once deposited, the challan (Dr TDS payable / Cr bank). */
    public void syncTds() {
        Set<Long> ded = new HashSet<>(), dep = new HashSet<>();
        for (Map<String, Object> e : jdbc.queryForList("""
                SELECT e.id, e.section, e.payee_name, e.payment_date, e.tds_paise, e.deposited_on, e.challan_no, i.invoice_no, i.counterparty_name
                FROM tds_entry e JOIN invoice i ON i.id=e.invoice_id WHERE e.tenant_id=? AND e.status='DEDUCT' AND e.tds_paise>0 AND i.status='CONFIRMED' ORDER BY e.id""", Tenant.current())) {
            long id = l(e.get("id")), tds = l(e.get("tds_paise"));
            String section = (String) e.get("section");
            ded.add(id);
            String narr = "TDS " + section + " deducted on " + e.get("invoice_no") + " — " + party(e.get("counterparty_name"));
            audited(ledger.postGeneric("tds_deduct", id, (String) e.get("payment_date"), narr, tdsDeductLines((String) e.get("counterparty_name"), section, tds)), "tds_deduct", id, narr);
            if (e.get("deposited_on") != null) {
                dep.add(id);
                String n2 = "TDS " + section + " deposited (challan " + Objects.toString(e.get("challan_no"), "-") + ") — " + party(e.get("counterparty_name"));
                audited(ledger.postGeneric("tds_deposit", id, (String) e.get("deposited_on"), n2, tdsDepositLines("TDS payable: " + section, tds)), "tds_deposit", id, n2);
            }
        }
        for (long id : staleIds("tds_deduct", ded)) ledger.unpost("tds_deduct", id);
        for (long id : staleIds("tds_deposit", dep)) ledger.unpost("tds_deposit", id);
    }

    /** Posts one salary journal per employee-month (ref id = payroll_line.id) and one deposit journal per month (ref id = yyyymm). */
    public void syncPayroll() {
        Set<Long> acc = new HashSet<>(), dep = new HashSet<>();
        for (Map<String, Object> p : jdbc.queryForList("SELECT * FROM payroll_line WHERE tenant_id=? ORDER BY id", Tenant.current())) {
            long id = l(p.get("id")), gross = l(p.get("gross_paise")), tds = l(p.get("tds_paise"));
            String month = (String) p.get("month");
            String date = YearMonth.parse(month).atEndOfMonth().toString();
            acc.add(id);
            String narr = "Salary " + month + " — " + (p.get("employee_name") == null || p.get("employee_name").toString().isBlank() ? p.get("employee_id") : p.get("employee_name")) + ("OLD".equals(p.get("regime")) ? " (OLD regime: TDS not computed, ask CA)" : "");
            audited(ledger.postGeneric("payroll", id, date, narr, payrollLines(gross, tds)), "payroll", id, narr);
        }
        for (Map<String, Object> m : jdbc.queryForList("SELECT month, SUM(tds_paise) AS tds, MAX(deposited_on) AS dep, MAX(challan_no) AS ch FROM payroll_line WHERE tenant_id=? AND tds_paise>0 AND deposited_on IS NOT NULL GROUP BY month", Tenant.current())) {
            String month = (String) m.get("month");
            long key = Long.parseLong(month.replace("-", ""));
            dep.add(key);
            String narr = "Salary TDS " + month + " deposited (challan " + Objects.toString(m.get("ch"), "-") + ")";
            audited(ledger.postGeneric("payroll_deposit", key, (String) m.get("dep"), narr, tdsDepositLines(TDS_SALARY, l(m.get("tds")))), "payroll_deposit", key, narr);
        }
        for (long id : staleIds("payroll", acc)) ledger.unpost("payroll", id);
        for (long id : staleIds("payroll_deposit", dep)) ledger.unpost("payroll_deposit", id);
    }

    public static final String ADVANCE_SUSPENSE = "Bank / cash (advance receipts not matched to a bank line)";
    /** Advance receipts: Dr Bank / Cr Advance from customers; adjustment against an invoice: Dr Advance / Cr Receivable.
     *  The GST on a taxed advance is reported in the returns (Table 11 / 3.1(a)) and booked with the invoice; it is not posted separately. */
    public void syncAdvances() {
        Set<Long> rec = new HashSet<>(), adj = new HashSet<>();
        for (Map<String, Object> a : jdbc.queryForList("SELECT a.*, t.account_label FROM advance_receipt a LEFT JOIN bank_txn t ON t.id=a.bank_txn_id WHERE a.tenant_id=? AND a.status='ACTIVE' ORDER BY a.id", Tenant.current())) {
            long id = l(a.get("id")), g = l(a.get("gross_paise"));
            String bank = a.get("account_label") != null ? "Bank: " + a.get("account_label") : ADVANCE_SUSPENSE, cust = "Advance from customer: " + party(a.get("customer_name"));
            rec.add(id);
            String narr = "Advance received — " + party(a.get("customer_name")) + " (" + a.get("kind") + ")";
            audited(ledger.postGeneric("advance", id, (String) a.get("receipt_date"), narr, List.of(new Line(bank, g, 0), new Line(cust, 0, g))), "advance", id, narr);
            for (Map<String, Object> x : jdbc.queryForList("SELECT x.*, i.invoice_no, i.invoice_date, i.counterparty_name FROM advance_adjustment x JOIN invoice i ON i.id=x.invoice_id WHERE x.advance_id=? ORDER BY x.id", id)) {
                long xid = l(x.get("id")), xg = l(x.get("gross_paise"));
                adj.add(xid);
                String n2 = "Advance adjusted against " + x.get("invoice_no") + " — " + party(a.get("customer_name"));
                audited(ledger.postGeneric("advance_adj", xid, (String) x.get("invoice_date"), n2, List.of(new Line(cust, xg, 0), new Line("Receivable: " + party(x.get("counterparty_name")), 0, xg))), "advance_adj", xid, n2);
            }
        }
        for (long id : staleIds("advance", rec)) ledger.unpost("advance", id);
        for (long id : staleIds("advance_adj", adj)) ledger.unpost("advance_adj", id);
    }

    public void syncAll() { syncBank(); syncTds(); syncPayroll(); syncAdvances(); }

    private List<Long> staleIds(String refType, Set<Long> keep) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT DISTINCT ref_id FROM journal WHERE tenant_id=? AND ref_type=?", Tenant.current(), refType))
            if (!keep.contains(l(r.get("ref_id")))) out.add(l(r.get("ref_id")));
        return out;
    }
}
