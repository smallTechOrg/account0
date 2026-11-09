package com.account0.scrutiny;

import java.math.BigDecimal;
import java.util.*;

/** Pure: line-by-line diff of filed vs recomputed 3B, and deterministic "questions to ask your CA". */
public final class ScrutinyEngine {
    private ScrutinyEngine() {}
    static final long TOL = 100; // ₹1

    public record Row(String key, String label, String head, long filed, long books, long diff, String status) {}

    public static List<Row> diff(Map<String, Map<String, Long>> filed, Map<String, Map<String, Long>> books) {
        List<Row> rows = new ArrayList<>();
        for (var line : Gstr3bFigures.LINES.entrySet()) {
            Map<String, Long> f = filed.getOrDefault(line.getKey(), Gstr3bFigures.zero()), b = books.getOrDefault(line.getKey(), Gstr3bFigures.zero());
            for (String h : Gstr3bFigures.HEADS) {
                if (line.getKey().startsWith("4") && h.equals("taxable")) continue;
                long fv = f.getOrDefault(h, 0L), bv = b.getOrDefault(h, 0L);
                if (fv == 0 && bv == 0) continue;
                rows.add(new Row(line.getKey(), line.getValue(), h, fv, bv, fv - bv, Math.abs(fv - bv) <= TOL ? "MATCH" : "DIFF"));
            }
        }
        return rows;
    }

    public static List<Map<String, String>> questions(List<Row> rows, Map<String, List<Map<String, Object>>> sourcesByKey, Long itcIn2bPaise) {
        List<Map<String, String>> q = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (Row r : rows) {
            if (r.status().equals("MATCH")) continue;
            String what = r.label() + " — " + r.head().toUpperCase(Locale.ROOT);
            String base = String.format("Filed %s vs %s per my books (difference %s).", rs(r.filed()), rs(r.books()), rs(Math.abs(r.diff())));
            String question, why;
            boolean more = r.diff() > 0;
            switch (r.key()) {
                case "3.1a", "3.1b", "3.1c", "3.1e" -> {
                    question = more ? "Which additional sales or adjustments are included in the filed figure? Please share the invoice list you used."
                            : "Which of my invoices were left out or moved to another month? " + list(sourcesByKey.get(r.key()));
                    why = "Outward supplies in 3B must match GSTR-1 for the same month; a gap usually means a missed invoice, a date cut-off difference or an amendment.";
                }
                case "3.1d" -> {
                    question = r.filed() == 0 ? "No reverse-charge tax was reported. My books show import-of-service / RCM purchases: " + list(sourcesByKey.get("3.1d")) + " Should RCM IGST have been paid on these under Sec 5(3) IGST Act?"
                            : more ? "Which other reverse-charge purchases did you include?" : "Which reverse-charge purchases were left out? " + list(sourcesByKey.get("3.1d"));
                    why = "Tax on reverse-charge supplies must be paid in cash in the month of supply (Sec 49(4)); missing it attracts interest u/s 50 and the matching ITC can only be taken once it is paid.";
                }
                case "4A5" -> {
                    question = more ? "ITC claimed is higher than my purchase invoices. Which invoices support the extra credit?" + (itcIn2bPaise != null && r.filed() > itcIn2bPaise + TOL ? " It is also higher than what my GSTR-2B shows (" + rs(itcIn2bPaise) + ")." : "")
                            : "ITC claimed is lower than my eligible purchases. Was some credit deferred (e.g. not yet in GSTR-2B) or treated as ineligible? " + list(sourcesByKey.get("4A5"));
                    why = "ITC can be claimed only if the invoice appears in GSTR-2B (Sec 16(2)(aa)); excess claims attract interest at 18% and penalty, while unclaimed credit is a cash cost to the company.";
                }
                case "4A2", "4A3" -> {
                    question = more ? "Which RCM payments does this credit relate to?" : "The RCM I paid should be available as credit in the same return — why was less claimed?";
                    why = "RCM paid in cash becomes input tax credit (Sec 16) if the purchase is for business.";
                }
                case "4B1" -> { question = "Which credits were reversed, and under which rule (17(5) / Rule 42 / Rule 43)?"; why = "Reversals reduce your credit; you should know what is being reversed and why."; }
                case "4C" -> { if (!done.add("4C")) continue; question = "Net ITC differs — this follows from the lines above; please reconcile 4A − 4B for me."; why = "Net ITC = 4A − 4B."; }
                default -> { question = "Please explain this difference."; why = ""; }
            }
            q.add(Map.of("key", r.key(), "about", what, "facts", base, "question", question, "why", why));
        }
        return q;
    }

    static String list(List<Map<String, Object>> src) {
        if (src == null || src.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("My invoices: ");
        src.stream().limit(6).forEach(s -> sb.append(s.get("invoice_no")).append(" (").append(s.get("party")).append(", ").append(rs(((Number) s.get("taxable_paise")).longValue())).append("); "));
        if (src.size() > 6) sb.append("and ").append(src.size() - 6).append(" more.");
        return sb.toString().trim();
    }
    static String rs(long p) { return "₹" + String.format(Locale.ROOT, "%,.2f", BigDecimal.valueOf(p, 2)); }
}
