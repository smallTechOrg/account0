package com.account0.payroll;

/**
 * Sec 192 projection for the NEW tax regime (default u/s 115BAC), FY 2025-26 slabs (Finance Act 2025).
 * The same slabs, the ₹12 lakh / ₹60,000 rebate and the ₹75,000 standard deduction continue for FY 2026-27 (Budget 2026, no change).
 * Pure arithmetic in paise. Old regime, surcharge-bearing incomes (> ₹50 lakh) and other income/deductions are NOT modelled — flagged, never guessed.
 */
public final class Salary192 {
    private Salary192() {}
    static final long R = 100;
    public static final long STD_DEDUCTION = 75_000 * R;
    public static final long REBATE_LIMIT_INCOME = 1_200_000 * R, REBATE_MAX = 60_000 * R;
    public static final long SURCHARGE_FLOOR = 5_000_000 * R;

    public record Result(long taxableIncome, long taxBeforeCess, long cess, long totalTax, boolean needsCaReview, String note) {}

    /** Slab tax on taxable income (paise), new regime. */
    public static long slabTax(long taxable) {
        long[][] slabs = {{400_000, 0}, {800_000, 5}, {1_200_000, 10}, {1_600_000, 15}, {2_000_000, 20}, {2_400_000, 25}};
        long tax = 0, prev = 0;
        for (long[] s : slabs) {
            long upper = s[0] * R;
            if (taxable > prev) tax += (Math.min(taxable, upper) - prev) * s[1] / 100;
            prev = upper;
        }
        if (taxable > prev) tax += (taxable - prev) * 30 / 100;
        return tax;
    }

    public static Result annualTax(long annualGross) {
        long taxable = Math.max(0, annualGross - STD_DEDUCTION);
        long tax = slabTax(taxable);
        String note = "New regime, std deduction ₹75,000.";
        if (taxable <= REBATE_LIMIT_INCOME) { long rebate = Math.min(tax, REBATE_MAX); tax -= rebate; if (rebate > 0) note += " Rebate u/s 87A applied."; }
        else { long excess = taxable - REBATE_LIMIT_INCOME; if (tax > excess) { tax = excess; note += " Marginal relief on rebate applied."; } }
        boolean review = taxable > SURCHARGE_FLOOR;
        if (review) note += " Income above ₹50 lakh attracts surcharge — NOT computed; have the CA compute this employee's TDS.";
        long cess = Math.floorDiv(tax * 4 + 50, 100);
        return new Result(taxable, tax, cess, tax + cess, review, note);
    }

    /** Nearest rupee (TDS is rounded to the rupee in practice; Sec 288B rounds income/tax to ₹10 and is not the rule here). */
    public static long roundRupee(long paise) { return Math.floorDiv(paise + 50, 100) * 100; }

    /**
     * TDS for this month: (annual tax on projected income − TDS already deducted this FY) / remaining months, never negative.
     * @param ytdGrossBefore gross paid in earlier months of the FY, @param thisGross this month's gross, @param monthsRemainingInclThis 1..12
     */
    /** Higher rate when the employee has no PAN (Sec 206AA / s.397(2)): the higher of the average rate and 20%; nil when no tax is payable on the projected income. */
    public static final int NO_PAN_RATE_BP = 2000;
    public static long monthlyTdsNoPan(long ytdGrossBefore, long thisGross, int monthsRemainingInclThis, long tdsDeductedBefore) {
        long normal = monthlyTds(ytdGrossBefore, thisGross, monthsRemainingInclThis, tdsDeductedBefore);
        if (annualTax(ytdGrossBefore + thisGross * monthsRemainingInclThis).totalTax() == 0) return 0;
        return Math.max(normal, roundRupee(thisGross * NO_PAN_RATE_BP / 10_000));
    }

    public static long monthlyTds(long ytdGrossBefore, long thisGross, int monthsRemainingInclThis, long tdsDeductedBefore) {
        long projected = ytdGrossBefore + thisGross * monthsRemainingInclThis;
        Result r = annualTax(projected);
        long remaining = Math.max(0, r.totalTax() - tdsDeductedBefore);
        return roundRupee(remaining / monthsRemainingInclThis);
    }
}
