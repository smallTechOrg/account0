package com.account0.gst;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One explainable number in a return: amounts + the rule applied + the invoices it came from. */
public class Figure {
    public final String key, label, rule, ref;
    public long taxable, igst, cgst, sgst, cess;
    public final List<Map<String, Object>> sources = new ArrayList<>();
    /** Optional: e.g. "PROVISIONAL" with a caveat shown next to the figure (null = final). */
    public String status, caveat;

    public Figure(String key, String label, String rule, String ref) { this.key = key; this.label = label; this.rule = rule; this.ref = ref; }

    public void add(Map<String, Object> inv, long sign, long tx, long ig, long cg, long sg, long ce) {
        taxable += sign * tx; igst += sign * ig; cgst += sign * cg; sgst += sign * sg; cess += sign * ce;
        sources.add(Map.of("id", inv.get("id"), "invoice_no", String.valueOf(inv.get("invoice_no")), "invoice_date", String.valueOf(inv.get("invoice_date")),
                "party", String.valueOf(inv.get("counterparty_name")), "doc_type", String.valueOf(inv.get("doc_type")),
                "taxable_paise", sign * tx, "tax_paise", sign * (ig + cg + sg + ce)));
    }
    public long tax() { return igst + cgst + sgst + cess; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("label", label); m.put("rule", rule); m.put("ref", ref); m.put("taxable_paise", taxable); m.put("igst_paise", igst);
        m.put("cgst_paise", cgst); m.put("sgst_paise", sgst); m.put("cess_paise", cess); m.put("sources", sources);
        if (status != null) { m.put("status", status); m.put("caveat", caveat); }
        return m;
    }
}
