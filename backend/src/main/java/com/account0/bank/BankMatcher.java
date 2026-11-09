package com.account0.bank;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure scoring of bank transactions against invoices. Score 0-100; strong >= 85 (auto), suggestion >= 50. */
public final class BankMatcher {
    private BankMatcher() {}
    public static final long TOL = 100; // ₹1
    public static final int AUTO = 85, SUGGEST = 50;

    /** isSale: receipt (credit) expected; outstanding = invoice total minus payments already matched. */
    public record Invoice(long id, boolean isSale, String no, String date, String party, long outstandingPaise) {}
    public record Txn(long id, String date, String description, String ref, long debit, long credit) {}
    public record Candidate(long invoiceId, int score, String note) {}

    static Set<String> tokens(String s) {
        Set<String> t = new HashSet<>();
        if (s == null) return t;
        for (String w : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").split("\\s+"))
            if (w.length() >= 4 && !List.of("PRIVATE", "LIMITED", "PVT", "LTD", "SOLUTIONS", "TECHNOLOGIES", "INDIA", "SERVICES", "CORP", "COMPANY", "NEFT", "IMPS", "UPI", "RTGS", "PAYMENT", "TRANSFER").contains(w)) t.add(w);
        return t;
    }
    static String compact(String s) { return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }

    public static Candidate score(Txn t, Invoice inv) {
        boolean receipt = t.credit() > 0;
        if (receipt != inv.isSale()) return null;
        long amt = receipt ? t.credit() : t.debit();
        if (inv.outstandingPaise() <= 0) return null;
        int score = 0; List<String> notes = new ArrayList<>();
        long diff = inv.outstandingPaise() - amt;
        boolean idHit = inv.no() != null && compact(inv.no()).length() >= 4 && compact(t.description() + t.ref()).contains(compact(inv.no()));
        Set<String> a = tokens(t.description()), b = tokens(inv.party()); a.retainAll(b);
        boolean nameHit = !a.isEmpty();
        if (Math.abs(diff) <= TOL) { score += 55; notes.add("amount equals the invoice"); }
        else if (diff > 0 && diff * 100 <= inv.outstandingPaise() * 12 && (idHit || nameHit)) { score += 30; notes.add("₹" + String.format(Locale.ROOT, "%,.2f", diff / 100.0) + " less than the invoice (" + (receipt ? "TDS withheld by the customer or bank charges?" : "short payment or part payment?") + ")"); }
        else if (diff > 0 && amt < inv.outstandingPaise() && idHit) { score += 20; notes.add("part payment"); }
        else return null;
        if (idHit) { score += 30; notes.add("invoice number " + inv.no() + " in narration"); }
        if (nameHit) { score += 12 + Math.min(8, a.size() * 4); notes.add("name match (" + String.join(", ", a) + ")"); }
        long days = ChronoUnit.DAYS.between(LocalDate.parse(inv.date()), LocalDate.parse(t.date()));
        if (days >= -3 && days <= 60) { score += days <= 30 ? 8 : 4; notes.add("paid " + days + " days after the invoice"); }
        else if (days < -3) score -= 30;
        else score -= 5;
        return new Candidate(inv.id(), Math.max(0, Math.min(100, score)), String.join("; ", notes));
    }

    /** Ranked candidates (score >= SUGGEST). */
    public static List<Candidate> rank(Txn t, List<Invoice> invoices) {
        List<Candidate> out = new ArrayList<>();
        for (Invoice i : invoices) { Candidate c = score(t, i); if (c != null && c.score() >= SUGGEST) out.add(c); }
        out.sort(Comparator.comparingInt(Candidate::score).reversed());
        return out;
    }
    /** Strong only when the best score >= AUTO and the runner-up is at least 15 points lower. */
    public static boolean unambiguousStrong(List<Candidate> ranked) {
        return !ranked.isEmpty() && ranked.get(0).score() >= AUTO && (ranked.size() == 1 || ranked.get(1).score() <= ranked.get(0).score() - 15);
    }
}
