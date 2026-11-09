package com.account0.tds;

import java.util.*;

/** Pure TDS computation for one payee + section within one financial year. Payments must be in date order. */
public final class TdsEngine {
    private TdsEngine() {}
    public record Payment(long id, long basePaise) {}
    /** status: DEDUCT | BELOW_THRESHOLD */
    public record Result(long id, long basePaise, int rateBp, long tdsPaise, String status, String reason) {}
    public static final int NO_PAN_RATE_BP = 2000; // Sec 206AA

    /** Nearest rupee, half up (Sec 288B), expressed in paise. */
    public static long tds(long basePaise, int rateBp) {
        long rupeesTimes10000 = basePaise * rateBp;                    // paise * bp
        long paise = Math.floorDiv(rupeesTimes10000 + 5000, 10000);   // round to paise
        return Math.floorDiv(paise + 50, 100) * 100;                   // round to rupee
    }

    public static List<Result> compute(TdsSections.Section s, TdsSections.PayeeType type, boolean hasPan, List<Payment> payments) {
        int rate = s.rate(type);
        boolean noPan = !hasPan;
        if (noPan) rate = Math.max(rate, NO_PAN_RATE_BP);
        List<Result> out = new ArrayList<>();
        long cum = 0, deductibleBase = 0, tdsSoFar = 0;
        boolean aggTriggered = false;
        for (Payment p : payments) {
            cum += p.basePaise();
            String why;
            if (s.excessOnly()) {
                deductibleBase = Math.max(0, cum - s.annualThreshold());
                why = deductibleBase > 0 ? "Year's total with this payee is above ₹" + (s.annualThreshold() / 100) + "; TDS only on the excess." : null;
            } else {
                boolean wasTriggered = aggTriggered;
                if (!aggTriggered && cum > s.annualThreshold()) {
                    aggTriggered = true;
                    deductibleBase = cum;   // catch-up: every earlier payment in the year becomes deductible
                    why = wasTriggered ? null : "Year's total with this payee crossed ₹" + (s.annualThreshold() / 100) + "; TDS now applies to the whole year's payments, including earlier ones.";
                } else if (aggTriggered) { deductibleBase += p.basePaise(); why = "Year's total threshold already crossed."; }
                else if (s.singleThreshold() != null && p.basePaise() > s.singleThreshold()) { deductibleBase += p.basePaise(); why = "Single payment exceeds ₹" + (s.singleThreshold() / 100) + "."; }
                else why = null;
            }
            long target = tds(deductibleBase, rate);
            long t = Math.max(0, target - tdsSoFar);
            tdsSoFar += t;
            if (t == 0 && deductibleBase == 0 || (t == 0 && why == null))
                out.add(new Result(p.id(), p.basePaise(), rate, 0, "BELOW_THRESHOLD", "Below the threshold so far (year total ₹" + String.format(Locale.ROOT, "%,.2f", cum / 100.0) + ")."));
            else
                out.add(new Result(p.id(), p.basePaise(), rate, t, t > 0 ? "DEDUCT" : "BELOW_THRESHOLD", (why == null ? "" : why + " ") + (noPan ? "No PAN given: higher rate 20% (Sec 206AA)." : "")));
        }
        return out;
    }
}
