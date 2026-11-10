package com.account0.tds;

import java.util.*;

/** Pure TDS computation for one payee + section within one financial year. Payments must be in date order. */
public final class TdsEngine {
    private TdsEngine() {}
    /** month = YYYY-MM of the payment/credit (needed for monthly-threshold sections such as 194I; null = its own month). */
    public record Payment(long id, long basePaise, String month) {
        public Payment(long id, long basePaise) { this(id, basePaise, null); }
    }
    /** status: DEDUCT | BELOW_THRESHOLD */
    public record Result(long id, long basePaise, int rateBp, long tdsPaise, String status, String reason) {}
    /** Default higher rate without PAN (Sec 206AA / s.397(2) Income-tax Act 2025); 194Q uses 5% (see TdsSections.noPanRateBp). */
    public static final int NO_PAN_RATE_BP = 2000;

    /** Nearest rupee, half up (TDS is rounded to the rupee in practice; Sec 288B itself rounds total income/tax to ₹10 and is not the rule here), in paise. */
    public static long tds(long basePaise, int rateBp) {
        long rupeesTimes10000 = basePaise * rateBp;                    // paise * bp
        long paise = Math.floorDiv(rupeesTimes10000 + 5000, 10000);   // round to paise
        return Math.floorDiv(paise + 50, 100) * 100;                   // round to rupee
    }

    static String rupees(long paise) { return String.format(Locale.ROOT, "%,d", paise / 100); }

    public static List<Result> compute(TdsSections.Section s, TdsSections.PayeeType type, boolean hasPan, List<Payment> payments) {
        int rate = s.rate(type);
        boolean noPan = !hasPan;
        if (noPan) rate = Math.max(rate, s.noPanRateBp());
        String panNote = noPan ? "No PAN given: higher rate " + (s.noPanRateBp() / 100.0) + "% (Sec 206AA / s.397(2) Income-tax Act 2025 — verify with CA)." : "";
        if (s.monthly()) return monthly(s, rate, panNote, payments);
        List<Result> out = new ArrayList<>();
        long cum = 0, deductibleBase = 0, tdsSoFar = 0;
        boolean aggTriggered = false;
        for (Payment p : payments) {
            cum += p.basePaise();
            String why;
            if (s.excessOnly()) {
                deductibleBase = Math.max(0, cum - s.annualThreshold());
                why = deductibleBase > 0 ? "Year's total with this payee is above ₹" + rupees(s.annualThreshold()) + "; TDS only on the excess." : null;
            } else if (s.annualThreshold() == 0) {
                deductibleBase = cum; why = "No threshold for this section: TDS from the first rupee.";
            } else {
                boolean wasTriggered = aggTriggered;
                if (!aggTriggered && cum > s.annualThreshold()) {
                    aggTriggered = true;
                    deductibleBase = cum;   // catch-up: every earlier payment in the year becomes deductible
                    why = wasTriggered ? null : "Year's total with this payee crossed ₹" + rupees(s.annualThreshold()) + "; TDS now applies to the whole year's payments, including earlier ones.";
                } else if (aggTriggered) { deductibleBase += p.basePaise(); why = "Year's total threshold already crossed."; }
                else if (s.singleThreshold() != null && p.basePaise() > s.singleThreshold()) { deductibleBase += p.basePaise(); why = "Single payment exceeds ₹" + rupees(s.singleThreshold()) + "."; }
                else why = null;
            }
            long target = tds(deductibleBase, rate);
            long t = Math.max(0, target - tdsSoFar);
            tdsSoFar += t;
            if (t == 0 && deductibleBase == 0 || (t == 0 && why == null))
                out.add(new Result(p.id(), p.basePaise(), rate, 0, "BELOW_THRESHOLD", "Below the threshold so far (year total ₹" + String.format(Locale.ROOT, "%,.2f", cum / 100.0) + ")."));
            else
                out.add(new Result(p.id(), p.basePaise(), rate, t, t > 0 ? "DEDUCT" : "BELOW_THRESHOLD", ((why == null ? "" : why + " ") + panNote).trim()));
        }
        return out;
    }

    /** Monthly threshold (194I): the rent of each month is tested on its own; once a month's total exceeds the threshold, all of that month's rent is taxed. */
    static List<Result> monthly(TdsSections.Section s, int rate, String panNote, List<Payment> payments) {
        List<Result> out = new ArrayList<>();
        Map<String, long[]> byMonth = new HashMap<>(); // month -> {cum, tdsSoFar}
        for (Payment p : payments) {
            String m = p.month() != null ? p.month() : "#" + p.id();
            long[] st = byMonth.computeIfAbsent(m, k -> new long[2]);
            st[0] += p.basePaise();
            if (st[0] <= s.monthlyThreshold()) {
                out.add(new Result(p.id(), p.basePaise(), rate, 0, "BELOW_THRESHOLD", "Rent for " + (p.month() == null ? "this month" : p.month()) + " is ₹" + String.format(Locale.ROOT, "%,.2f", st[0] / 100.0) + ", not above ₹" + rupees(s.monthlyThreshold()) + " for the month."));
                continue;
            }
            long t = Math.max(0, tds(st[0], rate) - st[1]);
            st[1] += t;
            out.add(new Result(p.id(), p.basePaise(), rate, t, t > 0 ? "DEDUCT" : "BELOW_THRESHOLD",
                    ("Rent for " + (p.month() == null ? "this month" : p.month()) + " exceeds ₹" + rupees(s.monthlyThreshold()) + " (threshold per month or part of a month, from 1 Apr 2025): TDS on the month's whole rent. " + panNote).trim()));
        }
        return out;
    }
}
