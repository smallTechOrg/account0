package com.account0.itc;

import java.util.*;

/** Pure matching of GSTR-2B documents against purchase invoices in the books. */
public final class ItcMatcher {
    private ItcMatcher() {}
    public static final long TOL = 100; // ₹1

    /** heads = {IGST, CGST, SGST, Cess} in paise (null = compare the total only); kind = INVOICE | CREDIT_NOTE | DEBIT_NOTE. */
    public record Book(long id, String ctin, String invNo, long taxPaise, String kind, long[] heads) {
        public Book(long id, String ctin, String invNo, long taxPaise) { this(id, ctin, invNo, taxPaise, "INVOICE", null); }
    }
    public record Doc(long id, String ctin, String docNo, long taxPaise, String kind, long[] heads) {
        public Doc(long id, String ctin, String docNo, long taxPaise) { this(id, ctin, docNo, taxPaise, "INVOICE", null); }
    }
    static final String[] HEAD_NAMES = {"IGST", "CGST", "SGST", "Cess"};
    /** null when every head agrees within ₹1; else a description of the first difference. */
    static String headDiff(Doc d, Book b) {
        if (d.heads() != null && b.heads() != null) {
            for (int i = 0; i < 4; i++) if (Math.abs(d.heads()[i] - b.heads()[i]) > TOL)
                return HEAD_NAMES[i] + " in 2B is " + (d.heads()[i] / 100.0) + " but your books show " + (b.heads()[i] / 100.0) + " (each tax head must agree — e.g. IGST charged instead of CGST+SGST).";
            return null;
        }
        return Math.abs(d.taxPaise() - b.taxPaise()) <= TOL ? null : "Tax in 2B is " + (d.taxPaise() / 100.0) + " but your books show " + (b.taxPaise() / 100.0) + ".";
    }
    static boolean sameKind(Doc d, Book b) { return Objects.equals(d.kind(), b.kind()); }
    /** status: MATCHED | AMOUNT_MISMATCH | POSSIBLE_MATCH | IN_2B_NOT_IN_BOOKS | IN_BOOKS_NOT_IN_2B */
    public record Result(String status, Long docId, Long bookId, String note) {}

    public static String norm(String no) {
        if (no == null) return "";
        String s = no.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return s.replaceFirst("^0+(?=.)", "");
    }
    public static String normGstin(String g) { return g == null ? "" : g.trim().toUpperCase(Locale.ROOT); }

    public static List<Result> match(List<Book> books, List<Doc> docs) {
        List<Result> out = new ArrayList<>();
        Set<Long> usedBooks = new HashSet<>(), usedDocs = new HashSet<>();
        // pass 1: exact on GSTIN + normalised invoice number
        for (Doc d : docs) for (Book b : books) {
            if (usedBooks.contains(b.id())) continue;
            if (sameKind(d, b) && normGstin(d.ctin()).equals(normGstin(b.ctin())) && norm(d.docNo()).equals(norm(b.invNo()))) {
                String diff = headDiff(d, b);
                out.add(new Result(diff == null ? "MATCHED" : "AMOUNT_MISMATCH", d.id(), b.id(), diff == null ? "GSTIN, document no and every tax head agree." : diff));
                usedBooks.add(b.id()); usedDocs.add(d.id()); break;
            }
        }
        // pass 2: fuzzy — same GSTIN, same tax amount, different invoice number (typo / prefix) and the invoice number is similar
        for (Doc d : docs) {
            if (usedDocs.contains(d.id())) continue;
            for (Book b : books) {
                if (usedBooks.contains(b.id())) continue;
                if (sameKind(d, b) && normGstin(d.ctin()).equals(normGstin(b.ctin())) && headDiff(d, b) == null && similar(norm(d.docNo()), norm(b.invNo()))) {
                    out.add(new Result("POSSIBLE_MATCH", d.id(), b.id(), "Same vendor and tax amount, but invoice numbers differ (" + d.docNo() + " in 2B vs " + b.invNo() + " in books). Confirm and ask the vendor to correct."));
                    usedBooks.add(b.id()); usedDocs.add(d.id()); break;
                }
            }
        }
        for (Doc d : docs) if (!usedDocs.contains(d.id())) out.add(new Result("IN_2B_NOT_IN_BOOKS", d.id(), null, "Appears in your GSTR-2B but you have no matching purchase invoice — a missing invoice, or not yours."));
        for (Book b : books) if (!usedBooks.contains(b.id())) out.add(new Result("IN_BOOKS_NOT_IN_2B", null, b.id(), "Not in GSTR-2B — vendor may not have filed GSTR-1 yet; ITC may not be claimable (Sec 16(2)(aa))."));
        return out;
    }

    static boolean similar(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.contains(b) || b.contains(a)) return true;
        return levenshtein(a, b) <= Math.max(1, Math.min(a.length(), b.length()) / 5);
    }
    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
