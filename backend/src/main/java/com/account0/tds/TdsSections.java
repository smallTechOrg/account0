package com.account0.tds;

import java.util.*;

/**
 * TDS sections supported for resident payees. Rates/thresholds as understood for FY 2025-26 onwards (Finance Act 2025 changes included).
 * Assumed: section numbers of the Income-tax Act, 1961 are used as the user-facing reference; the Income-tax Act, 2025 (in force 1 Apr 2026)
 * renumbers them — confirm numbering/rates with the CA before filing. Payments to non-residents (Sec 195) are deliberately NOT computed.
 */
public final class TdsSections {
    private TdsSections() {}
    public static final long RUPEE = 100;
    /** rateBp / rateBpIndHuf in basis points (100 = 1%). Thresholds in paise; null = none. */
    public record Section(String code, String label, int rateBp, int rateBpIndHuf, Long singleThreshold, long annualThreshold, boolean excessOnly, String ref, String note) {
        public int rate(PayeeType t) { return t == PayeeType.INDIVIDUAL_HUF ? rateBpIndHuf : rateBp; }
    }
    public enum PayeeType { COMPANY_OR_FIRM, INDIVIDUAL_HUF }

    public static final LinkedHashMap<String, Section> ALL = new LinkedHashMap<>();
    private static void add(Section s) { ALL.put(s.code(), s); }
    static {
        add(new Section("194C", "Payment to contractors / sub-contractors (works contract, labour, transport, advertising)", 200, 100, 30_000 * RUPEE, 100_000 * RUPEE, false,
                "Sec 194C", "1% if payee is an individual/HUF, 2% otherwise. Deduct if a single payment exceeds ₹30,000 or the year's total to the payee exceeds ₹1,00,000."));
        add(new Section("194J_PROF", "Professional services, director fees (194J(b)) — 10%", 1000, 1000, null, 50_000 * RUPEE, false,
                "Sec 194J(b)", "10% on fees for professional services/royalty/director remuneration. Threshold ₹50,000 per payee per year (raised from ₹30,000 from 1 Apr 2025)."));
        add(new Section("194J_TECH", "Fees for technical services, call-centre (194J(a)) — 2%", 200, 200, null, 50_000 * RUPEE, false,
                "Sec 194J(a)", "2% on fees for technical services (not professional). Threshold ₹50,000 per payee per year."));
        add(new Section("194I_PM", "Rent of plant & machinery / equipment (194I(a)) — 2%", 200, 200, null, 600_000 * RUPEE, false,
                "Sec 194I(a)", "2% on rent of plant, machinery, equipment. Threshold ₹6,00,000 per year (from 1 Apr 2025)."));
        add(new Section("194I_LB", "Rent of land, building, furniture (194I(b)) — 10%", 1000, 1000, null, 600_000 * RUPEE, false,
                "Sec 194I(b)", "10% on rent of land/building/furniture. Threshold ₹6,00,000 per year (from 1 Apr 2025)."));
        add(new Section("194H", "Commission or brokerage — 2%", 200, 200, null, 20_000 * RUPEE, false,
                "Sec 194H", "2% (reduced from 5% w.e.f. 1 Oct 2024). Threshold ₹20,000 per year (from 1 Apr 2025)."));
        add(new Section("194Q", "Purchase of goods from a resident seller — 0.1%", 10, 10, null, 5_000_000 * RUPEE, true,
                "Sec 194Q", "0.1% on the amount above ₹50 lakh with one seller in a year. Applies only if YOUR turnover in the preceding FY exceeded ₹10 crore — confirm that before using it."));
    }
    public static final String NONE = "NONE";
    public static boolean valid(String code) { return NONE.equals(code) || ALL.containsKey(code); }
}
