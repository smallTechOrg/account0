package com.account0.compliance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Pure due-date generator. A calendar "FY 2026-27" lists every obligation whose DUE DATE falls between 1 Apr 2026 and 31 Mar 2027.
 * So it contains the monthly returns for Mar 2026..Feb 2027, and the year-end filings that relate to FY 2025-26 (ROC, ITR-6, GSTR-9, Q4 TDS...).
 * Assumed: statutory (un-extended) dates for a monthly GST filer (not QRMP), private limited company with a statutory auditor.
 * Governments often extend dates by notification — always cross-check the portal before relying on a date.
 */
public final class ComplianceCalendar {
    private ComplianceCalendar() {}
    public enum Applicability { ALWAYS, IF_EMPLOYEES, IF_APPLICABLE }
    public record Item(String code, String title, String category, String period, LocalDate due, String form, String ref, String why, Applicability applicability) {
        public String key() { return code + "|" + period; }
    }
    public record Options(LocalDate agmDate, boolean taxAudit) {}

    public static int startYear(String fy) {
        if (fy == null || !fy.matches("\\d{4}-\\d{2}") || (Integer.parseInt(fy.substring(0, 4)) + 1) % 100 != Integer.parseInt(fy.substring(5))) throw new IllegalArgumentException("FY must look like 2026-27");
        return Integer.parseInt(fy.substring(0, 4));
    }

    public static List<Item> generate(String fy, Options o) {
        int y = startYear(fy);
        List<Item> out = new ArrayList<>();
        // monthly: return periods Mar(y) .. Feb(y+1) -> due in following month
        for (YearMonth ym = YearMonth.of(y, 3); !ym.isAfter(YearMonth.of(y + 1, 2)); ym = ym.plusMonths(1)) {
            String p = ym.toString(); YearMonth nx = ym.plusMonths(1); String label = ym.getMonth().name().substring(0, 1) + ym.getMonth().name().substring(1).toLowerCase() + " " + ym.getYear();
            out.add(new Item("GSTR1", "GSTR-1 for " + label, "GST", p, nx.atDay(11), "GSTR-1", "Sec 37 CGST Act; Rule 59", "Outward supplies statement; your customers' ITC depends on it.", Applicability.ALWAYS));
            out.add(new Item("GSTR3B", "GSTR-3B for " + label + " + tax payment", "GST", p, nx.atDay(20), "GSTR-3B", "Sec 39 CGST Act; Rule 61", "Summary return and payment of tax; late fee and 18% interest on delay (Sec 47, 50).", Applicability.ALWAYS));
            out.add(new Item("TDS_DEPOSIT", "Deposit TDS deducted in " + label, "TDS", p, ym.getMonthValue() == 3 ? LocalDate.of(ym.getYear(), 4, 30) : nx.atDay(7), "Challan 281", "Rule 30 Income-tax Rules (7th of next month; 30 April for March)", "Late deposit costs 1.5% per month interest (Sec 201(1A)).", Applicability.ALWAYS));
            out.add(new Item("PF", "PF contribution + ECR for " + label, "Payroll", p, nx.atDay(15), "EPF ECR", "EPF Act 1952, Para 38 of EPF Scheme", "Due on the 15th; damages and interest on delay. Applies once you have 20+ employees (or opted in).", Applicability.IF_EMPLOYEES));
            out.add(new Item("ESI", "ESI contribution for " + label, "Payroll", p, nx.atDay(15), "ESIC challan", "ESI Act 1948", "Applies to employees earning up to ₹21,000/month once you have 10+ employees.", Applicability.IF_EMPLOYEES));
        }
        // quarterly TDS returns: Q4 of FY y-1 falls in this window, Q1..Q3 of FY y
        String prevFy = (y - 1) + "-" + String.format("%02d", y % 100), curFy = fy;
        quarter(out, prevFy, 4, LocalDate.of(y, 5, 31), LocalDate.of(y, 6, 15));
        quarter(out, curFy, 1, LocalDate.of(y, 7, 31), LocalDate.of(y, 8, 15));
        quarter(out, curFy, 2, LocalDate.of(y, 10, 31), LocalDate.of(y, 11, 15));
        quarter(out, curFy, 3, LocalDate.of(y + 1, 1, 31), LocalDate.of(y + 1, 2, 15));
        out.add(new Item("FORM16", "Issue Form 16 to employees (FY " + prevFy + ")", "Payroll", "FY" + prevFy, LocalDate.of(y, 6, 15), "Form 16", "Rule 31 Income-tax Rules", "Salary TDS certificate; employees need it to file their returns.", Applicability.IF_EMPLOYEES));
        // advance tax for FY y
        int[][] adv = {{6, 15, 15}, {9, 15, 45}, {12, 15, 75}};
        for (int[] a : adv) out.add(new Item("ADVANCE_TAX", "Advance tax — " + a[2] + "% of the year's tax", "Income tax", "FY" + fy + "-" + a[2], LocalDate.of(y, a[0], a[1]), "Challan 280", "Sec 207-211 Income-tax Act", "Interest u/s 234B/234C if short. Needed if the year's tax liability is ₹10,000 or more.", Applicability.IF_APPLICABLE));
        out.add(new Item("ADVANCE_TAX", "Advance tax — 100% of the year's tax", "Income tax", "FY" + fy + "-100", LocalDate.of(y + 1, 3, 15), "Challan 280", "Sec 207-211 Income-tax Act", "Interest u/s 234B/234C if short.", Applicability.IF_APPLICABLE));
        // annual: obligations for FY y-1 falling due during FY y
        LocalDate agm = o.agmDate() != null ? o.agmDate() : LocalDate.of(y, 9, 30);
        String rel = "FY" + prevFy;
        out.add(new Item("AGM", "Annual General Meeting for FY " + prevFy, "ROC", rel, agm, "AGM", "Sec 96 Companies Act 2013 (within 6 months of year end)", "Adopts the audited financials. The other ROC dates below move with the AGM date (set it in Company profile / settings).", Applicability.ALWAYS));
        out.add(new Item("ADT1", "Form ADT-1 (auditor appointment)", "ROC", rel, agm.plusDays(15), "ADT-1", "Sec 139 Companies Act; Rule 4 (within 15 days of AGM)", "Only when an auditor is appointed or re-appointed at this AGM.", Applicability.IF_APPLICABLE));
        out.add(new Item("AOC4", "Form AOC-4 (financial statements) for FY " + prevFy, "ROC", rel, agm.plusDays(30), "AOC-4", "Sec 137 Companies Act (within 30 days of AGM)", "Late fee ₹100/day with no cap.", Applicability.ALWAYS));
        out.add(new Item("MGT7A", "Form MGT-7A (annual return — small company) for FY " + prevFy, "ROC", rel, agm.plusDays(60), "MGT-7A", "Sec 92 Companies Act (within 60 days of AGM)", "Small companies and OPCs file MGT-7A; others MGT-7.", Applicability.ALWAYS));
        out.add(new Item("DIR3KYC", "DIR-3 KYC for every director", "ROC", rel, LocalDate.of(y, 9, 30), "DIR-3 KYC", "Rule 12A Companies (Appointment and Qualification of Directors) Rules", "DIN is deactivated if missed; ₹5,000 to reactivate.", Applicability.ALWAYS));
        out.add(new Item("DPT3", "Form DPT-3 (return of deposits / outstanding loans)", "ROC", rel, LocalDate.of(y, 6, 30), "DPT-3", "Rule 16 Companies (Acceptance of Deposits) Rules", "Required even with no deposits if the company has loans from directors/others that are exempt deposits.", Applicability.IF_APPLICABLE));
        if (o.taxAudit()) out.add(new Item("TAX_AUDIT", "Tax audit report (3CA/3CD) for FY " + prevFy, "Income tax", rel, LocalDate.of(y, 9, 30), "3CA-3CD", "Sec 44AB / 139(1) Income-tax Act", "File one month before the ITR due date.", Applicability.IF_APPLICABLE));
        out.add(new Item("ITR6", "Income-tax return ITR-6 for FY " + prevFy, "Income tax", rel, LocalDate.of(y, 10, 31), "ITR-6", "Sec 139(1) Income-tax Act", "Companies file ITR-6 online with a digital signature; 30 Nov if transfer pricing applies.", Applicability.ALWAYS));
        out.add(new Item("GSTR9", "GSTR-9 annual return for FY " + prevFy, "GST", rel, LocalDate.of(y, 12, 31), "GSTR-9", "Sec 44 CGST Act", "Optional if turnover is up to ₹2 crore; GSTR-9C reconciliation above ₹5 crore.", Applicability.IF_APPLICABLE));
        out.sort(Comparator.comparing(Item::due).thenComparing(Item::code));
        return out;
    }

    private static void quarter(List<Item> out, String fy, int q, LocalDate returnDue, LocalDate certDue) {
        String period = fy + "-Q" + q;
        out.add(new Item("TDS_26Q", "Form 26Q (non-salary TDS return) FY " + fy + " Q" + q, "TDS", period, returnDue, "26Q", "Sec 200(3) Income-tax Act; Rule 31A", "Late filing fee ₹200/day (Sec 234E) until filed.", Applicability.ALWAYS));
        out.add(new Item("TDS_24Q", "Form 24Q (salary TDS return) FY " + fy + " Q" + q, "TDS", period, returnDue, "24Q", "Sec 200(3) Income-tax Act; Rule 31A", "Late filing fee ₹200/day (Sec 234E) until filed.", Applicability.IF_EMPLOYEES));
        out.add(new Item("FORM16A", "Issue Form 16A to vendors FY " + fy + " Q" + q, "TDS", period, certDue, "Form 16A", "Rule 31 Income-tax Rules", "Vendors need it to claim credit for the TDS you deducted.", Applicability.ALWAYS));
    }
}
