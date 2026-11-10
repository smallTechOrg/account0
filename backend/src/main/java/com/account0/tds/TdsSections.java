package com.account0.tds;

import java.util.*;

/**
 * TDS sections supported for resident payees. Rates/thresholds as understood for FY 2025-26 onwards (Finance Act 2025 changes included).
 * Section numbers of the Income-tax Act, 1961 are the user-facing reference; from 1 Apr 2026 the Income-tax Act, 2025 moves these payments into
 * the s.393(1) table with numeric payment codes (shown beside each section where known — confirm the codes with the CA). Payments to non-residents
 * (Sec 195 / s.393(2)) are deliberately NOT computed.
 */
public final class TdsSections {
    private TdsSections() {}
    public static final long RUPEE = 100;
    /**
     * rateBp / rateBpIndHuf in basis points (100 = 1%). Thresholds in paise; singleThreshold null = none; annualThreshold 0 = no threshold at all.
     * monthlyThreshold (194I since 1 Apr 2025): TDS applies when the rent for a month (or part of a month) exceeds it; the annual threshold is then unused.
     * noPanRateBp: Sec 206AA / s.397(2) higher rate when the payee gives no PAN (5% for 194Q, 20% otherwise).
     */
    public record Section(String code, String label, int rateBp, int rateBpIndHuf, Long singleThreshold, long annualThreshold, boolean excessOnly, String ref, String note,
                          Long monthlyThreshold, int noPanRateBp, String newCode) {
        public int rate(PayeeType t) { return t == PayeeType.INDIVIDUAL_HUF ? rateBpIndHuf : rateBp; }
        public boolean monthly() { return monthlyThreshold != null; }
    }
    public enum PayeeType { COMPANY_OR_FIRM, INDIVIDUAL_HUF }

    public static final LinkedHashMap<String, Section> ALL = new LinkedHashMap<>();
    private static void add(Section s) { ALL.put(s.code(), s); }
    static {
        add(new Section("194C", "Payment to contractors / sub-contractors (works contract, labour, transport, advertising)", 200, 100, 30_000 * RUPEE, 100_000 * RUPEE, false,
                "Sec 194C", "1% if payee is an individual/HUF, 2% otherwise. Deduct if a single payment exceeds ₹30,000 or the year's total to the payee exceeds ₹1,00,000.", null, 2000, "1023 (ind/HUF) / 1024 (others)"));
        add(new Section("194J_PROF", "Professional services, royalty (194J(b)) — 10%", 1000, 1000, null, 50_000 * RUPEE, false,
                "Sec 194J(b)", "10% on fees for professional services and royalty. Threshold ₹50,000 per payee per year (raised from ₹30,000 from 1 Apr 2025). Director fees are a separate section (194J_DIR, no threshold).", null, 2000, "1027"));
        add(new Section("194J_DIR", "Director's remuneration / sitting fees (194J(1)(ba)) — 10%, no threshold", 1000, 1000, null, 0, false,
                "Sec 194J(1)(ba)", "10% on any remuneration, fees or commission to a director that is not salary. There is NO threshold — deduct from the first rupee.", null, 2000, "1028"));
        add(new Section("194J_TECH", "Fees for technical services, call-centre (194J(a)) — 2%", 200, 200, null, 50_000 * RUPEE, false,
                "Sec 194J(a)", "2% on fees for technical services (not professional). Threshold ₹50,000 per payee per year.", null, 2000, null));
        add(new Section("194I_PM", "Rent of plant & machinery / equipment (194I(a)) — 2%", 200, 200, null, 0, false,
                "Sec 194I(a)", "2% on rent of plant, machinery, equipment when the rent exceeds ₹50,000 for a month or part of a month (from 1 Apr 2025; earlier ₹2,40,000 a year). Account0 treats each invoice as the rent for the month of its date.", 50_000 * RUPEE, 2000, "1008"));
        add(new Section("194I_LB", "Rent of land, building, furniture (194I(b)) — 10%", 1000, 1000, null, 0, false,
                "Sec 194I(b)", "10% on rent of land/building/furniture when the rent exceeds ₹50,000 for a month or part of a month (from 1 Apr 2025). Account0 treats each invoice as the rent for the month of its date.", 50_000 * RUPEE, 2000, "1009"));
        add(new Section("194H", "Commission or brokerage — 2%", 200, 200, null, 20_000 * RUPEE, false,
                "Sec 194H", "2% (reduced from 5% w.e.f. 1 Oct 2024). Threshold ₹20,000 per year (from 1 Apr 2025).", null, 2000, "1006"));
        add(new Section("194Q", "Purchase of goods from a resident seller — 0.1%", 10, 10, null, 5_000_000 * RUPEE, true,
                "Sec 194Q", "0.1% on the amount above ₹50 lakh with one seller in a year. Applies only if YOUR turnover in the preceding FY exceeded ₹10 crore — confirm that before using it. Without the seller's PAN the rate is 5%.", null, 500, null));
    }
    public static final String NONE = "NONE";
    public static boolean valid(String code) { return NONE.equals(code) || ALL.containsKey(code); }
}
