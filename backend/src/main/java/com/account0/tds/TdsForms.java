package com.account0.tds;

/**
 * FY-aware TDS form names. The Income-tax Rules, 2026 (in force 1 Apr 2026, with the Income-tax Act, 2025) renumber the forms:
 * 24Q -> Form 138, 26Q -> Form 140, 27Q -> Form 144, Form 16 -> Form 130, Form 16A -> Form 131. Returns and certificates for
 * periods up to FY 2025-26 (incl. Q4 FY 2025-26 filed in May 2026 and Form 16 for FY 2025-26) keep the old names. Due dates are unchanged.
 */
public final class TdsForms {
    private TdsForms() {}
    /** fy like "2026-27". */
    public static boolean newRules(String fy) { return fy != null && fy.length() >= 4 && Integer.parseInt(fy.substring(0, 4)) >= 2026; }
    public static String salaryReturn(String fy) { return newRules(fy) ? "Form 138 (was 24Q)" : "Form 24Q"; }
    public static String nonSalaryReturn(String fy) { return newRules(fy) ? "Form 140 (was 26Q)" : "Form 26Q"; }
    public static String nonResidentReturn(String fy) { return newRules(fy) ? "Form 144 (was 27Q)" : "Form 27Q"; }
    public static String salaryCertificate(String fy) { return newRules(fy) ? "Form 130 (was Form 16)" : "Form 16"; }
    public static String nonSalaryCertificate(String fy) { return newRules(fy) ? "Form 131 (was Form 16A)" : "Form 16A"; }
    public static String fileTag(String fy, String old, String neu) { return newRules(fy) ? neu : old; }
}
