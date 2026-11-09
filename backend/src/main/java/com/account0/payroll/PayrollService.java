package com.account0.payroll;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Money;
import com.account0.common.Tenant;
import com.account0.tds.TdsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.*;

@Service
public class PayrollService {
    private final JdbcTemplate jdbc; private final AuditService audit;
    public PayrollService(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false;
        for (char c : line.toCharArray()) {
            if (c == '"') q = !q; else if (c == ',' && !q) { out.add(cur.toString().trim()); cur.setLength(0); } else cur.append(c);
        }
        out.add(cur.toString().trim()); return out;
    }

    /** CSV columns (header required, any order): employee_id,name,pan,month,gross[,regime]. gross = monthly taxable gross salary in rupees. */
    public Map<String, Object> importCsv(byte[] bytes) {
        String[] lines = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "").split("\\R");
        if (lines.length < 2) throw ApiException.bad("CSV needs a header and at least one row.");
        List<String> h = splitCsv(lines[0].toLowerCase(Locale.ROOT));
        for (String need : List.of("employee_id", "month", "gross")) if (!h.contains(need)) throw ApiException.bad("CSV header must include: employee_id, name, pan, month (YYYY-MM), gross [, regime]. Missing: " + need);
        int n = 0; Set<String> emps = new TreeSet<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> c = splitCsv(lines[i]);
            Map<String, String> row = new HashMap<>();
            for (int k = 0; k < h.size() && k < c.size(); k++) row.put(h.get(k), c.get(k));
            String month = row.getOrDefault("month", "");
            if (!month.matches("\\d{4}-(0[1-9]|1[0-2])")) throw ApiException.bad("Row " + (i + 1) + ": month must be YYYY-MM, got '" + month + "'");
            long gross;
            try { gross = Money.toPaise(row.getOrDefault("gross", "")); } catch (Exception e) { throw ApiException.bad("Row " + (i + 1) + ": gross is not a number"); }
            if (gross < 0) throw ApiException.bad("Row " + (i + 1) + ": gross cannot be negative");
            String pan = row.getOrDefault("pan", "").toUpperCase(Locale.ROOT);
            if (!pan.isEmpty() && !pan.matches("[A-Z]{5}[0-9]{4}[A-Z]")) throw ApiException.bad("Row " + (i + 1) + ": invalid PAN " + pan);
            String regime = row.getOrDefault("regime", "NEW").toUpperCase(Locale.ROOT);
            if (!regime.equals("NEW") && !regime.equals("OLD")) throw ApiException.bad("Row " + (i + 1) + ": regime must be NEW or OLD");
            String emp = row.getOrDefault("employee_id", "");
            if (emp.isBlank()) throw ApiException.bad("Row " + (i + 1) + ": employee_id missing");
            jdbc.update("""
                    INSERT INTO payroll_line(tenant_id, employee_id, employee_name, pan, month, fy, gross_paise, regime) VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT(tenant_id, employee_id, month) DO UPDATE SET employee_name=excluded.employee_name, pan=excluded.pan, fy=excluded.fy, gross_paise=excluded.gross_paise, regime=excluded.regime""",
                    Tenant.current(), emp, row.getOrDefault("name", ""), pan.isEmpty() ? null : pan, month, TdsService.fyOf(month + "-01"), gross, regime);
            emps.add(emp); n++;
        }
        audit.record("user", "payroll", "import", "import", "rows", null, n, "payroll CSV imported");
        recompute();
        return Map.of("rows", n, "employees", emps.size());
    }

    /** Deterministic Sec 192 recompute for every employee-month in FY order. */
    public void recompute() {
        Map<String, List<Map<String, Object>>> byEmpFy = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM payroll_line WHERE tenant_id=? ORDER BY employee_id, month", Tenant.current()))
            byEmpFy.computeIfAbsent(r.get("employee_id") + "|" + r.get("fy"), k -> new ArrayList<>()).add(r);
        for (List<Map<String, Object>> l : byEmpFy.values()) {
            long ytdGross = 0, ytdTds = 0;
            for (Map<String, Object> r : l) {
                YearMonth ym = YearMonth.parse((String) r.get("month"));
                int remaining = ym.getMonthValue() >= 4 ? 16 - ym.getMonthValue() : 4 - ym.getMonthValue(); // months from this one to March inclusive
                long gross = ((Number) r.get("gross_paise")).longValue();
                long projected = ytdGross + gross * remaining;
                long tds; String note; boolean review;
                Salary192.Result res = Salary192.annualTax(projected);
                if ("OLD".equals(r.get("regime"))) { tds = 0; note = "Old regime selected — not computed. Needs the employee's declarations (80C, HRA...); ask your CA."; review = true; }
                else { tds = Salary192.monthlyTds(ytdGross, gross, remaining, ytdTds); note = res.note(); review = res.needsCaReview(); }
                jdbc.update("UPDATE payroll_line SET projected_annual_paise=?, annual_tax_paise=?, tds_paise=?, needs_ca_review=?, note=? WHERE id=?", projected, res.totalTax(), tds, review ? 1 : 0, note, r.get("id"));
                ytdGross += gross; ytdTds += tds;
            }
        }
    }

    public List<Map<String, Object>> lines(String fy) {
        return jdbc.queryForList("SELECT * FROM payroll_line WHERE tenant_id=? AND fy=? ORDER BY month, employee_id", Tenant.current(), fy);
    }

    public List<Map<String, Object>> monthSummary(String fy) {
        return jdbc.queryForList("SELECT month, COUNT(*) AS employees, SUM(gross_paise) AS gross_paise, SUM(tds_paise) AS tds_paise, MAX(needs_ca_review) AS needs_ca_review, MAX(deposited_on) AS deposited_on FROM payroll_line WHERE tenant_id=? AND fy=? GROUP BY month ORDER BY month", Tenant.current(), fy);
    }

    public Map<String, Object> deposit(String month, String challan, String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw ApiException.bad("deposit date YYYY-MM-DD required");
        int n = jdbc.update("UPDATE payroll_line SET deposited_on=?, challan_no=? WHERE tenant_id=? AND month=? AND tds_paise>0", date, challan, Tenant.current(), month);
        audit.record("user", "payroll", month, "deposit", "deposited_on", null, date, "challan " + challan);
        return Map.of("month", month, "updated", n);
    }

    /** 24Q-style salary deduction data for a quarter. */
    public String csv24q(String fy, int quarter) {
        StringBuilder sb = new StringBuilder("Employee id,Name,PAN,Month,Gross salary,TDS deducted,Deposited on,Challan no\n");
        for (Map<String, Object> r : lines(fy)) {
            if (TdsService.quarterOf(r.get("month") + "-01") != quarter) continue;
            sb.append(TdsService.esc(r.get("employee_id"))).append(',').append(TdsService.esc(r.get("employee_name"))).append(',').append(r.get("pan") == null ? "PANNOTAVBL" : r.get("pan")).append(',').append(r.get("month")).append(',')
              .append(((Number) r.get("gross_paise")).longValue() / 100.0).append(',').append(((Number) r.get("tds_paise")).longValue() / 100.0).append(',').append(TdsService.esc(r.get("deposited_on"))).append(',').append(TdsService.esc(r.get("challan_no"))).append('\n');
        }
        return sb.toString();
    }

    public List<Map<String, Object>> alerts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT month, SUM(tds_paise) AS tds, MAX(needs_ca_review) AS rv FROM payroll_line WHERE tenant_id=? GROUP BY month", Tenant.current())) {
            if (((Number) r.get("rv")).intValue() == 1) out.add(TdsService.alert("PAYROLL_CA_REVIEW", "MEDIUM", (String) r.get("month"), "Payroll for " + r.get("month") + " has employees that Account0 does not compute (old regime or income above ₹50 lakh).", "Their Sec 192 TDS must be worked out by your CA.", "month:" + r.get("month")));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT month, SUM(tds_paise) AS tds FROM payroll_line WHERE tenant_id=? AND tds_paise>0 AND deposited_on IS NULL GROUP BY month", Tenant.current())) {
            YearMonth ym = YearMonth.parse((String) r.get("month"));
            java.time.LocalDate due = ym.getMonthValue() == 3 ? java.time.LocalDate.of(ym.getYear(), 4, 30) : ym.plusMonths(1).atDay(7);
            if (java.time.LocalDate.now().isAfter(due))
                out.add(TdsService.alert("SALARY_TDS_DEPOSIT_OVERDUE", "HIGH", (String) r.get("month"), "Salary TDS of ₹" + String.format(Locale.ROOT, "%,.2f", ((Number) r.get("tds")).longValue() / 100.0) + " for " + r.get("month") + " was due by " + due + " and no deposit is recorded.", "Interest at 1.5% per month from the date of deduction (Sec 201(1A)).", "month:" + r.get("month")));
        }
        return out;
    }
}
