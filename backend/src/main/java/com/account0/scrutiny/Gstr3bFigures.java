package com.account0.scrutiny;

import com.account0.common.Money;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.*;

/** Normalised 3B figures (paise) keyed like Account0's figure keys, + mapping from the portal's GSTR-3B JSON. */
public final class Gstr3bFigures {
    private Gstr3bFigures() {}
    public static final String[] HEADS = {"taxable", "igst", "cgst", "sgst", "cess"};
    public static final LinkedHashMap<String, String> LINES = new LinkedHashMap<>();
    static {
        LINES.put("3.1a", "3.1(a) Outward taxable supplies"); LINES.put("3.1b", "3.1(b) Zero-rated supplies"); LINES.put("3.1c", "3.1(c) Nil/exempt supplies");
        LINES.put("3.1d", "3.1(d) Inward supplies liable to reverse charge"); LINES.put("3.1e", "3.1(e) Non-GST outward supplies");
        LINES.put("4A1", "4A(1) ITC — import of goods"); LINES.put("4A2", "4A(2) ITC — import of services"); LINES.put("4A3", "4A(3) ITC — inward RCM (other)");
        LINES.put("4A4", "4A(4) ITC — inward supplies from ISD"); LINES.put("4A5", "4A(5) ITC — all other"); LINES.put("4B1", "4B(1) ITC reversed (Rules 38/42/43, Sec 17(5))"); LINES.put("4C", "4C Net ITC available");
    }

    static long p(JsonNode n, String k) {
        JsonNode v = n == null ? null : n.get(k);
        if (v == null || v.isNull()) return 0;
        return Money.toPaise(new BigDecimal(v.asText("0").isBlank() ? "0" : v.asText("0")));
    }
    static Map<String, Long> heads(JsonNode n) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("taxable", p(n, "txval")); m.put("igst", p(n, "iamt")); m.put("cgst", p(n, "camt")); m.put("sgst", p(n, "samt")); m.put("cess", p(n, "csamt"));
        return m;
    }

    /** Maps the GST portal's GSTR-3B JSON (as downloaded / as in the offline utility) to normalised figures. */
    public static Map<String, Map<String, Long>> fromPortalJson(JsonNode root) {
        JsonNode j = root.has("data") ? root.get("data") : root;
        Map<String, Map<String, Long>> f = new LinkedHashMap<>();
        JsonNode sup = j.path("sup_details");
        f.put("3.1a", heads(sup.get("osup_det"))); f.put("3.1b", heads(sup.get("osup_zero"))); f.put("3.1c", heads(sup.get("osup_nil_exmp")));
        f.put("3.1d", heads(sup.get("isup_rev"))); f.put("3.1e", heads(sup.get("osup_nongst")));
        JsonNode itc = j.path("itc_elg");
        Map<String, String> avl = Map.of("IMPG", "4A1", "IMPS", "4A2", "ISRC", "4A3", "ISD", "4A4", "OTH", "4A5");
        for (String k : List.of("4A1", "4A2", "4A3", "4A4", "4A5", "4B1")) f.put(k, zero());
        for (JsonNode a : itc.path("itc_avl")) { String k = avl.get(a.path("ty").asText()); if (k != null) f.put(k, heads(a)); }
        for (JsonNode a : itc.path("itc_rev")) if ("RUL".equals(a.path("ty").asText())) f.put("4B1", heads(a));
        f.put("4C", heads(itc.get("itc_net")));
        if (!root.toString().contains("sup_details")) throw new IllegalArgumentException("This does not look like a GSTR-3B JSON (no sup_details).");
        return f;
    }

    public static Map<String, Long> zero() { Map<String, Long> m = new LinkedHashMap<>(); for (String h : HEADS) m.put(h, 0L); return m; }

    /** Extracts Account0's own computed figures from Gstr3bCalculator output into the same shape. */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Long>> fromComputed(Map<String, Object> g3) {
        Map<String, Map<String, Long>> f = new LinkedHashMap<>();
        for (String t : List.of("table31", "table4"))
            for (Map<String, Object> fig : (List<Map<String, Object>>) g3.get(t)) {
                String k = (String) fig.get("key");
                if (!LINES.containsKey(k)) continue;
                Map<String, Long> m = new LinkedHashMap<>();
                m.put("taxable", ((Number) fig.get("taxable_paise")).longValue()); m.put("igst", ((Number) fig.get("igst_paise")).longValue());
                m.put("cgst", ((Number) fig.get("cgst_paise")).longValue()); m.put("sgst", ((Number) fig.get("sgst_paise")).longValue()); m.put("cess", ((Number) fig.get("cess_paise")).longValue());
                f.put(k, m);
            }
        return f;
    }
}
