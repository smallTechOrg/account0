package com.account0.gst;

import com.account0.invoice.SupplyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Deterministic GSTR-1 from CONFIRMED SALE invoices of a period. Pure.
 * Produces (a) a summary of each table with rules + sources and (b) a JSON in the shape of the GST portal
 * offline tool (best effort — validate in the offline tool before upload).
 */
public final class Gstr1Calculator {
    private Gstr1Calculator() {}
    /** B2C Large threshold: invoice value > ₹1 lakh inter-state to unregistered (Notification 12/2024-CT, w.e.f. 1-Aug-2024). */
    public static final long B2CL_THRESHOLD_PAISE = 100_000_00L;
    static final DateTimeFormatter PORTAL_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    static BigDecimal r(long p) { return BigDecimal.valueOf(p, 2); }
    static String d(Object iso) { return LocalDate.parse((String) iso).format(PORTAL_DATE); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> lines(Map<String, Object> inv) { return (List<Map<String, Object>>) inv.getOrDefault("lines", List.of()); }

    /** Items grouped by rate. If an invoice has no lines, rate is derived from header tax/taxable. */
    static List<Map<String, Object>> items(Map<String, Object> inv) {
        Map<String, long[]> byRate = new TreeMap<>(Comparator.comparing(BigDecimal::new));
        List<Map<String, Object>> ls = lines(inv);
        if (ls.isEmpty()) {
            long tx = l(inv.get("taxable_paise")), tax = l(inv.get("igst_paise")) + l(inv.get("cgst_paise")) + l(inv.get("sgst_paise"));
            String rate = tx == 0 ? "0" : BigDecimal.valueOf(tax * 100).divide(BigDecimal.valueOf(tx), 0, java.math.RoundingMode.HALF_UP).toPlainString();
            byRate.put(rate, new long[]{tx, l(inv.get("igst_paise")), l(inv.get("cgst_paise")), l(inv.get("sgst_paise")), l(inv.get("cess_paise"))});
        } else for (Map<String, Object> ln : ls) {
            String rate = ln.get("gst_rate") == null ? "0" : new BigDecimal((String) ln.get("gst_rate")).stripTrailingZeros().toPlainString();
            long[] a = byRate.computeIfAbsent(rate, k -> new long[5]);
            a[0] += l(ln.get("taxable_paise")); a[1] += l(ln.get("igst_paise")); a[2] += l(ln.get("cgst_paise")); a[3] += l(ln.get("sgst_paise")); a[4] += l(ln.get("cess_paise"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 1;
        for (var e : byRate.entrySet()) {
            long[] a = e.getValue();
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("txval", r(a[0])); det.put("rt", new BigDecimal(e.getKey()));
            det.put("iamt", r(a[1])); det.put("camt", r(a[2])); det.put("samt", r(a[3])); det.put("csamt", r(a[4]));
            out.add(Map.of("num", n++ * 100 + 1, "itm_det", det));
        }
        return out;
    }

    public static Map<String, Object> compute(List<Map<String, Object>> invoices, String ourGstin, String ourState, String period) {
        return compute(invoices, ourGstin, ourState, period, List.of());
    }

    /** cancelledNos: numbers of sales INVOICES that were issued and then cancelled (Table 13 "cancelled"); excluded from every figure. */
    public static Map<String, Object> compute(List<Map<String, Object>> invoices, String ourGstin, String ourState, String period, List<String> cancelledNos) {
        return computeDocs(invoices, ourGstin, ourState, period, cancelledNos.stream().map(n -> Map.<String, Object>of("invoice_no", n, "doc_type", "INVOICE")).toList());
    }

    /** cancelled: {invoice_no, doc_type} of sales documents issued and then cancelled. */
    public static Map<String, Object> computeDocs(List<Map<String, Object>> invoices, String ourGstin, String ourState, String period, List<Map<String, Object>> cancelled) {
        Map<String, List<Map<String, Object>>> b2b = new TreeMap<>(), cdnr = new TreeMap<>();
        Map<String, List<Map<String, Object>>> b2cl = new TreeMap<>();
        List<Map<String, Object>> cdnur = new ArrayList<>();
        Map<String, Map<String, Object>> b2cs = new TreeMap<>();
        List<Map<String, Object>> expWop = new ArrayList<>(), expWp = new ArrayList<>();
        Map<String, Map<String, Object>> hsnB2b = new TreeMap<>(), hsnB2c = new TreeMap<>();
        // Table 8: sply_ty -> {nil, exempt, non-GST}
        Map<String, long[]> nil = new LinkedHashMap<>();
        for (String k : List.of("INTRB2B", "INTRAB2B", "INTRB2C", "INTRAB2C")) nil.put(k, new long[3]);
        List<String> blockers = new ArrayList<>();
        Map<String, Figure> figs = new LinkedHashMap<>();
        Figure fB2b = fig(figs, "4A", "Table 4A — B2B invoices", "Sales to registered buyers, invoice-wise, with buyer GSTIN.", "Sec 37 CGST Act; Rule 59");
        Figure fB2cl = fig(figs, "5", "Table 5 — B2C (Large)", "Inter-state sales to unregistered buyers where invoice value exceeds ₹1 lakh, invoice-wise.", "Rule 59(4)(b); Notification 12/2024-CT");
        Figure fExp = fig(figs, "6A", "Table 6A — Exports", "Export invoices. WOPAY = under LUT without IGST; WPAY = with IGST.", "Sec 16 IGST Act");
        Figure fB2cs = fig(figs, "7", "Table 7 — B2C (Others)", "All other sales to unregistered buyers, consolidated by place of supply and rate.", "Rule 59(4)(c)");
        Figure fNil = fig(figs, "8", "Table 8 — Nil rated, exempted and non-GST", "Value of nil/exempt/non-GST sales.", "Rule 59(4)(d)");
        Figure fCdn = fig(figs, "9B", "Table 9B — Credit/Debit notes (registered)", "Credit and debit notes issued to registered buyers, netted: credit notes reduce (negative), debit notes add.", "Sec 34 CGST Act");
        Figure fCdnur = fig(figs, "9B-UR", "Table 9B — Credit/Debit notes (unregistered, incl. export notes: CDNUR)", "Credit and debit notes on export invoices (type EXPWP / EXPWOP). Also credit/debit notes on B2C Large invoices (type B2CL). Netted: credit notes negative, debit notes positive.", "Sec 34 CGST Act; Rule 53");
        List<Map<String, Object>> issued = new ArrayList<>(); List<String> notes = new ArrayList<>();

        for (Map<String, Object> inv : invoices) {
            if (!"SALE".equals(inv.get("direction"))) continue;
            SupplyType t = SupplyType.valueOf((String) inv.get("supply_type"));
            String pos = (String) inv.get("place_of_supply");
            long tx = l(inv.get("taxable_paise")), ig = l(inv.get("igst_paise")), cg = l(inv.get("cgst_paise")), sg = l(inv.get("sgst_paise")), ce = l(inv.get("cess_paise")), val = l(inv.get("total_paise"));
            boolean note = !"INVOICE".equals(inv.get("doc_type"));
            issued.add(Map.of("invoice_no", Objects.toString(inv.get("invoice_no"), ""), "doc_type", Objects.toString(inv.get("doc_type"), "INVOICE")));
            long nsign = "CREDIT_NOTE".equals(inv.get("doc_type")) ? -1 : 1;
            Map<String, Object> base = new LinkedHashMap<>();
            if (note) {
                base.put("ntty", "CREDIT_NOTE".equals(inv.get("doc_type")) ? "C" : "D"); base.put("nt_num", inv.get("invoice_no")); base.put("nt_dt", d(inv.get("invoice_date")));
            } else { base.put("inum", inv.get("invoice_no")); base.put("idt", d(inv.get("invoice_date"))); }
            base.put("val", r(val));
            switch (t) {
                case B2B, SEZ_LUT, SEZ_PAID -> {
                    base.put("pos", pos); base.put("rchrg", "N");
                    base.put("inv_typ", t == SupplyType.B2B ? "R" : t == SupplyType.SEZ_LUT ? "SEWOP" : "SEWP");
                    base.put("itms", items(inv));
                    String ctin = (String) inv.get("counterparty_gstin");
                    if (note) { cdnr.computeIfAbsent(ctin, k -> new ArrayList<>()).add(base); fCdn.add(inv, nsign, tx, ig, cg, sg, ce); }
                    else { b2b.computeIfAbsent(ctin, k -> new ArrayList<>()).add(base); fB2b.add(inv, 1, tx, ig, cg, sg, ce); }
                    hsn(hsnB2b, inv, "CREDIT_NOTE".equals(inv.get("doc_type")) ? -1 : 1);
                }
                case B2C -> {
                    boolean inter = pos != null && !pos.equals(ourState);
                    long sign = "CREDIT_NOTE".equals(inv.get("doc_type")) ? -1 : 1;
                    long orig = l(inv.get("original_total_paise"));
                    if (inter && note && orig > B2CL_THRESHOLD_PAISE) {
                        // note against a B2CL invoice -> CDNUR type B2CL (not netted into B2CS)
                        base.put("typ", "B2CL"); base.put("pos", pos); base.put("itms", items(inv));
                        cdnur.add(base); fCdnur.add(inv, nsign, tx, ig, cg, sg, ce);
                    } else if (inter && !note && val > B2CL_THRESHOLD_PAISE) {
                        base.put("itms", items(inv));
                        b2cl.computeIfAbsent(pos, k -> new ArrayList<>()).add(base); fB2cl.add(inv, 1, tx, ig, cg, sg, ce);
                    } else {
                        if (inter && note && inv.get("original_total_paise") == null)
                            notes.add("Note " + inv.get("invoice_no") + " is inter-state to an unregistered buyer, but its original invoice (" + inv.get("original_invoice_no") + ") is not in Account0: if that invoice was B2CL (value > ₹1 lakh) the note belongs in CDNUR type B2CL, not B2CS. Netted into B2CS for now.");
                        for (Map<String, Object> it : items(inv)) {
                            @SuppressWarnings("unchecked") Map<String, Object> det = (Map<String, Object>) it.get("itm_det");
                            String key = (inter ? "INTER" : "INTRA") + "|" + pos + "|" + det.get("rt");
                            Map<String, Object> row = b2cs.computeIfAbsent(key, k -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("sply_ty", inter ? "INTER" : "INTRA"); m.put("pos", pos); m.put("typ", "OE"); m.put("rt", det.get("rt"));
                                m.put("txval", BigDecimal.ZERO); m.put("iamt", BigDecimal.ZERO); m.put("camt", BigDecimal.ZERO); m.put("samt", BigDecimal.ZERO); m.put("csamt", BigDecimal.ZERO); return m; });
                            for (String k : List.of("txval", "iamt", "camt", "samt", "csamt")) row.put(k, ((BigDecimal) row.get(k)).add(((BigDecimal) det.get(k)).multiply(BigDecimal.valueOf(sign))));
                        }
                        fB2cs.add(inv, sign, tx, ig, cg, sg, ce);
                    }
                    hsn(hsnB2c, inv, sign);
                }
                case EXPORT_LUT, EXPORT_PAID -> {
                    base.put("itms", items(inv).stream().map(it -> { @SuppressWarnings("unchecked") Map<String, Object> det = (Map<String, Object>) it.get("itm_det");
                        Map<String, Object> m = new LinkedHashMap<>(); m.put("txval", det.get("txval")); m.put("rt", det.get("rt")); m.put("iamt", det.get("iamt")); m.put("csamt", det.get("csamt")); return (Map<String, Object>) m; }).toList());
                    long esign = "CREDIT_NOTE".equals(inv.get("doc_type")) ? -1 : 1;
                    if (note) {
                        base.put("typ", t == SupplyType.EXPORT_LUT ? "EXPWOP" : "EXPWP");
                        cdnur.add(base); fCdnur.add(inv, nsign, tx, ig, cg, sg, ce);
                    } else {
                        (t == SupplyType.EXPORT_LUT ? expWop : expWp).add(base);
                        fExp.add(inv, 1, tx, ig, cg, sg, ce);
                    }
                    hsn(hsnB2c, inv, esign);
                }
                case NIL_EXEMPT_OUT, NON_GST_OUT -> {
                    boolean reg = inv.get("counterparty_gstin") != null && !inv.get("counterparty_gstin").toString().isBlank();
                    boolean inter = ourState != null && pos != null && !pos.equals(ourState);
                    String k = (inter ? "INTR" : "INTRA") + (reg ? "B2B" : "B2C");
                    int col = t == SupplyType.NON_GST_OUT ? 2 : "NIL".equals(inv.get("nil_type")) ? 0 : 1;
                    if (t == SupplyType.NIL_EXEMPT_OUT && inv.get("nil_type") == null)
                        notes.add("Invoice " + inv.get("invoice_no") + ": nil-rated or exempt not stated — reported as EXEMPT in Table 8. Set it on the invoice if it is nil-rated.");
                    nil.get(k)[col] += nsign * tx;
                    fNil.add(inv, nsign, tx, 0, 0, 0, 0);
                    if (t == SupplyType.NIL_EXEMPT_OUT) hsn(reg ? hsnB2b : hsnB2c, inv, nsign);   // nil/exempt supplies are part of the HSN summary
                }
                default -> {}
            }
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("gstin", ourGstin);
        json.put("fp", period.substring(5, 7) + period.substring(0, 4));
        if (!b2b.isEmpty()) json.put("b2b", b2b.entrySet().stream().map(e -> Map.of("ctin", e.getKey(), "inv", e.getValue())).toList());
        if (!b2cl.isEmpty()) json.put("b2cl", b2cl.entrySet().stream().map(e -> Map.of("pos", e.getKey(), "inv", e.getValue())).toList());
        if (!b2cs.isEmpty()) json.put("b2cs", new ArrayList<>(b2cs.values()));
        List<Map<String, Object>> exp = new ArrayList<>();
        if (!expWop.isEmpty()) exp.add(Map.of("exp_typ", "WOPAY", "inv", expWop));
        if (!expWp.isEmpty()) exp.add(Map.of("exp_typ", "WPAY", "inv", expWp));
        if (!exp.isEmpty()) json.put("exp", exp);
        if (!cdnur.isEmpty()) json.put("cdnur", cdnur);
        if (!cdnr.isEmpty()) json.put("cdnr", cdnr.entrySet().stream().map(e -> Map.of("ctin", e.getKey(), "nt", e.getValue())).toList());
        List<Map<String, Object>> nilRows = new ArrayList<>();
        for (var e : nil.entrySet()) { long[] v = e.getValue(); if (v[0] != 0 || v[1] != 0 || v[2] != 0) nilRows.add(Map.of("sply_ty", e.getKey(), "nil_amt", r(v[0]), "expt_amt", r(v[1]), "ngsup_amt", r(v[2]))); }
        if (!nilRows.isEmpty()) json.put("nil", Map.of("inv", nilRows));
        Map<String, Object> hsn = new LinkedHashMap<>();
        hsn.put("hsn_b2b", numbered(hsnB2b)); hsn.put("hsn_b2c", numbered(hsnB2c));
        json.put("hsn", hsn);
        Table13 t13 = table13(issued, cancelled);
        if (!t13.docDet().isEmpty()) json.put("doc_issue", Map.of("doc_det", t13.docDet()));
        for (Map<String, Map<String, Object>> agg : List.of(hsnB2b, hsnB2c))
            for (Map<String, Object> h : agg.values()) {
                String code = Objects.toString(h.get("hsn_sc"), "");
                if (!code.matches("\\d{4}(\\d{2}(\\d{2})?)?")) blockers.add("HSN/SAC '" + code + "' (" + h.get("desc") + ") is not a valid 4/6/8-digit code. Since the May 2025 period the portal only accepts codes from its HSN list — enter the SAC on the invoice lines (e.g. 998314 IT design/development, 998315 hosting) before exporting the JSON.");
            }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", figs.values().stream().map(Figure::toMap).toList());
        out.put("hsnB2b", numbered(hsnB2b)); out.put("hsnB2c", numbered(hsnB2c));
        Map<String, Object> di = new LinkedHashMap<>();
        di.put("from", t13.series().isEmpty() ? "" : t13.series().get(0).get("from")); di.put("to", t13.series().isEmpty() ? "" : t13.series().get(0).get("to"));
        di.put("count", t13.total()); di.put("cancelled", t13.cancelled()); di.put("netIssued", t13.total() - t13.cancelled()); di.put("series", t13.series());
        di.put("rule", "Table 13 (mandatory since the May 2025 period): one row per document series — invoices (1), debit notes (4), credit notes (5) — with the serial range, total, cancelled and net issued. Cancellations come from documents you marked as cancelled in Account0; a series with gaps is flagged.");
        out.put("documentsIssued", di);
        notes.addAll(t13.notes());
        out.put("blockers", blockers);
        out.put("portalJson", json);
        out.put("notes", notes);
        return out;
    }

    record Table13(List<Map<String, Object>> docDet, List<Map<String, Object>> series, int total, int cancelled, List<String> notes) {}
    static final java.util.regex.Pattern TRAILING = java.util.regex.Pattern.compile("^(.*?)(\\d+)$");

    /** Groups documents by nature (1 invoices, 4 debit notes, 5 credit notes) and series prefix; sorts by the trailing number. */
    static Table13 table13(List<Map<String, Object>> issued, List<Map<String, Object>> cancelled) {
        record D(String no, String prefix, long n, boolean cancelled) {}
        Map<Integer, Map<String, List<D>>> g = new TreeMap<>();
        List<String> notes = new ArrayList<>();
        int total = 0, canc = 0;
        for (int pass = 0; pass < 2; pass++)
            for (Map<String, Object> d : pass == 0 ? issued : cancelled) {
                String no = Objects.toString(d.get("invoice_no"), "").trim();
                String dt = Objects.toString(d.get("doc_type"), "INVOICE");
                int nature = "DEBIT_NOTE".equals(dt) ? 4 : "CREDIT_NOTE".equals(dt) ? 5 : 1;
                var m = TRAILING.matcher(no);
                String prefix = m.matches() ? m.group(1) : no; long n = -1;
                if (m.matches()) try { n = Long.parseLong(m.group(2)); } catch (NumberFormatException ignored) {}
                g.computeIfAbsent(nature, k -> new TreeMap<>()).computeIfAbsent(prefix, k -> new ArrayList<>()).add(new D(no, prefix, n, pass == 1));
                total++; if (pass == 1) canc++;
            }
        List<Map<String, Object>> docDet = new ArrayList<>(), series = new ArrayList<>();
        String[] names = {"", "Invoices for outward supply", "", "", "Debit notes", "Credit notes"};
        for (var e : g.entrySet()) {
            List<Map<String, Object>> docs = new ArrayList<>(); int num = 1;
            for (var ser : e.getValue().entrySet()) {
                List<D> l = new ArrayList<>(ser.getValue());
                l.sort(Comparator.comparingLong(D::n).thenComparing(D::no));
                int c = (int) l.stream().filter(D::cancelled).count();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("num", num++); row.put("from", l.get(0).no()); row.put("to", l.get(l.size() - 1).no()); row.put("totnum", l.size()); row.put("cancel", c); row.put("net_issue", l.size() - c);
                docs.add(row);
                Map<String, Object> sv = new LinkedHashMap<>(row); sv.put("doc_num", e.getKey()); sv.put("nature", names[e.getKey()]); sv.put("series", ser.getKey());
                long lo = l.get(0).n(), hi = l.get(l.size() - 1).n();
                if (lo >= 0 && hi - lo + 1 > l.size()) {
                    sv.put("gap", true);
                    notes.add("Table 13: series '" + ser.getKey() + "' runs " + l.get(0).no() + " … " + l.get(l.size() - 1).no() + " (" + (hi - lo + 1) + " numbers) but only " + l.size() + " document(s) are in Account0 for this period — upload or mark the missing ones as cancelled.");
                }
                series.add(sv);
            }
            docDet.add(Map.of("doc_num", e.getKey(), "docs", docs));
        }
        return new Table13(docDet, series, total, canc, notes);
    }

    static Figure fig(Map<String, Figure> m, String k, String label, String rule, String ref) { Figure f = new Figure(k, label, rule, ref); m.put(k, f); return f; }

    static void hsn(Map<String, Map<String, Object>> agg, Map<String, Object> inv, long sign) {
        List<Map<String, Object>> ls = lines(inv);
        if (ls.isEmpty()) {
            Map<String, Object> one = new HashMap<>();
            for (String k : List.of("taxable_paise", "igst_paise", "cgst_paise", "sgst_paise", "cess_paise")) one.put(k, inv.get(k));
            one.put("hsn_sac", "UNKNOWN");
            ls = List.of(one);
        }
        for (Map<String, Object> ln : ls) {
            String code = ln.get("hsn_sac") == null ? "UNKNOWN" : ln.get("hsn_sac").toString();
            String rate = ln.get("gst_rate") == null ? "0" : new BigDecimal(ln.get("gst_rate").toString()).stripTrailingZeros().toPlainString();
            Map<String, Object> row = agg.computeIfAbsent(code + "|" + rate, k -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("hsn_sc", code); m.put("desc", Objects.toString(ln.get("description"), ""));
                m.put("uqc", code.startsWith("99") ? "NA" : "OTH"); m.put("qty", BigDecimal.ZERO); m.put("rt", new BigDecimal(rate));
                for (String x : List.of("txval", "iamt", "camt", "samt", "csamt")) m.put(x, BigDecimal.ZERO); return m; });
            row.put("txval", ((BigDecimal) row.get("txval")).add(r(sign * l(ln.get("taxable_paise")))));
            row.put("iamt", ((BigDecimal) row.get("iamt")).add(r(sign * l(ln.get("igst_paise")))));
            row.put("camt", ((BigDecimal) row.get("camt")).add(r(sign * l(ln.get("cgst_paise")))));
            row.put("samt", ((BigDecimal) row.get("samt")).add(r(sign * l(ln.get("sgst_paise")))));
            row.put("csamt", ((BigDecimal) row.get("csamt")).add(r(sign * l(ln.get("cess_paise")))));
            if (!code.startsWith("99") && ln.get("quantity") != null) try { row.put("qty", ((BigDecimal) row.get("qty")).add(new BigDecimal(ln.get("quantity").toString()))); } catch (Exception ignored) {}
        }
    }
    static List<Map<String, Object>> numbered(Map<String, Map<String, Object>> agg) {
        List<Map<String, Object>> out = new ArrayList<>(); int n = 1;
        for (Map<String, Object> m : agg.values()) { Map<String, Object> x = new LinkedHashMap<>(); x.put("num", n++); x.putAll(m); out.add(x); }
        return out;
    }
}
