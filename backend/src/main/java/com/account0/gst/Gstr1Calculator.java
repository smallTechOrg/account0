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

    /** cancelledNos: numbers of sales documents that were issued and then cancelled (Table 13 "cancelled"); excluded from every figure. */
    public static Map<String, Object> compute(List<Map<String, Object>> invoices, String ourGstin, String ourState, String period, List<String> cancelledNos) {
        Map<String, List<Map<String, Object>>> b2b = new TreeMap<>(), cdnr = new TreeMap<>();
        Map<String, List<Map<String, Object>>> b2cl = new TreeMap<>();
        List<Map<String, Object>> cdnur = new ArrayList<>();
        Map<String, Map<String, Object>> b2cs = new TreeMap<>();
        List<Map<String, Object>> expWop = new ArrayList<>(), expWp = new ArrayList<>();
        Map<String, Map<String, Object>> hsnB2b = new TreeMap<>(), hsnB2c = new TreeMap<>();
        long nilIntraB2b = 0, nilInterB2b = 0, exemptAmt = 0, ngsup = 0;
        Map<String, Figure> figs = new LinkedHashMap<>();
        Figure fB2b = fig(figs, "4A", "Table 4A — B2B invoices", "Sales to registered buyers, invoice-wise, with buyer GSTIN.", "Sec 37 CGST Act; Rule 59");
        Figure fB2cl = fig(figs, "5", "Table 5 — B2C (Large)", "Inter-state sales to unregistered buyers where invoice value exceeds ₹1 lakh, invoice-wise.", "Rule 59(4)(b); Notification 12/2024-CT");
        Figure fExp = fig(figs, "6A", "Table 6A — Exports", "Export invoices. WOPAY = under LUT without IGST; WPAY = with IGST.", "Sec 16 IGST Act");
        Figure fB2cs = fig(figs, "7", "Table 7 — B2C (Others)", "All other sales to unregistered buyers, consolidated by place of supply and rate.", "Rule 59(4)(c)");
        Figure fNil = fig(figs, "8", "Table 8 — Nil rated, exempted and non-GST", "Value of nil/exempt/non-GST sales.", "Rule 59(4)(d)");
        Figure fCdn = fig(figs, "9B", "Table 9B — Credit/Debit notes (registered)", "Credit and debit notes issued to registered buyers.", "Sec 34 CGST Act");
        Figure fCdnur = fig(figs, "9B-UR", "Table 9B — Credit/Debit notes (unregistered, incl. export notes: CDNUR)", "Credit and debit notes on export invoices (type EXPWP / EXPWOP). Shown as issued (credit notes reduce your liability).", "Sec 34 CGST Act; Rule 53");
        int docCount = 0; List<String> nums = new ArrayList<>(); List<String> notes = new ArrayList<>();

        for (Map<String, Object> inv : invoices) {
            if (!"SALE".equals(inv.get("direction"))) continue;
            SupplyType t = SupplyType.valueOf((String) inv.get("supply_type"));
            String pos = (String) inv.get("place_of_supply");
            long tx = l(inv.get("taxable_paise")), ig = l(inv.get("igst_paise")), cg = l(inv.get("cgst_paise")), sg = l(inv.get("sgst_paise")), ce = l(inv.get("cess_paise")), val = l(inv.get("total_paise"));
            boolean note = !"INVOICE".equals(inv.get("doc_type"));
            docCount++; nums.add((String) inv.get("invoice_no"));
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
                    if (note) { cdnr.computeIfAbsent(ctin, k -> new ArrayList<>()).add(base); fCdn.add(inv, 1, tx, ig, cg, sg, ce); }
                    else { b2b.computeIfAbsent(ctin, k -> new ArrayList<>()).add(base); fB2b.add(inv, 1, tx, ig, cg, sg, ce); }
                    hsn(hsnB2b, inv, "CREDIT_NOTE".equals(inv.get("doc_type")) ? -1 : 1);
                }
                case B2C -> {
                    boolean inter = pos != null && !pos.equals(ourState);
                    long sign = "CREDIT_NOTE".equals(inv.get("doc_type")) ? -1 : 1;
                    if (inter && !note && val > B2CL_THRESHOLD_PAISE) {
                        base.put("itms", items(inv));
                        b2cl.computeIfAbsent(pos, k -> new ArrayList<>()).add(base); fB2cl.add(inv, 1, tx, ig, cg, sg, ce);
                    } else {
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
                        cdnur.add(base); fCdnur.add(inv, 1, tx, ig, cg, sg, ce);
                    } else {
                        (t == SupplyType.EXPORT_LUT ? expWop : expWp).add(base);
                        fExp.add(inv, 1, tx, ig, cg, sg, ce);
                    }
                    hsn(hsnB2c, inv, esign);
                }
                case NIL_EXEMPT_OUT -> { exemptAmt += tx; fNil.add(inv, 1, tx, 0, 0, 0, 0); }
                case NON_GST_OUT -> { ngsup += tx; fNil.add(inv, 1, tx, 0, 0, 0, 0); }
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
        if (exemptAmt + ngsup > 0) json.put("nil", Map.of("inv", List.of(Map.of("sply_ty", "INTRAB2C", "expt_amt", r(exemptAmt), "nil_amt", BigDecimal.ZERO, "ngsup_amt", r(ngsup)))));
        Map<String, Object> hsn = new LinkedHashMap<>();
        hsn.put("hsn_b2b", numbered(hsnB2b)); hsn.put("hsn_b2c", numbered(hsnB2c));
        json.put("hsn", hsn);
        int cancelled = cancelledNos.size(); int totIssued = docCount + cancelled;
        nums.addAll(cancelledNos);
        Collections.sort(nums);
        if (totIssued > 0) json.put("doc_issue", Map.of("doc_det", List.of(Map.of("doc_num", 1, "docs", List.of(Map.of("num", 1, "from", nums.get(0), "to", nums.get(nums.size() - 1), "totnum", totIssued, "cancel", cancelled, "net_issue", docCount))))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", figs.values().stream().map(Figure::toMap).toList());
        out.put("hsnB2b", numbered(hsnB2b)); out.put("hsnB2c", numbered(hsnB2c));
        out.put("documentsIssued", Map.of("from", nums.isEmpty() ? "" : nums.get(0), "to", nums.isEmpty() ? "" : nums.get(nums.size() - 1), "count", totIssued, "cancelled", cancelled, "netIssued", docCount,
                "rule", "Table 13: serial range of documents issued, with cancelled ones counted separately. Cancellations come from invoices you marked as cancelled in Account0 — mark any others (documents never uploaded) with your CA."));
        out.put("portalJson", json);
        out.put("notes", notes);
        return out;
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
