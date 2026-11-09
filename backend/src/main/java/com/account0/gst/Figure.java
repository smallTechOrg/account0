package com.account0.gst;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One explainable number in a return: amounts + the rule applied + the invoices it came from. */
public class Figure {
    public final String key, label, rule, ref;
    public long taxable, igst, cgst, sgst, cess;
    public final List<Map<String, Object>> sources = new ArrayList<>();

    public Figure(String key, String label, String rule, String ref) { this.key = key; this.label = label; this.rule = rule; this.ref = ref; }

    public void add(Map<String, Object> inv, long sign, long tx, long ig, long cg, long sg, long ce) {
        taxable += sign * tx; igst += sign * ig; cgst += sign * cg; sgst += sign * sg; cess += sign * ce;
        sources.add(Map.of("id", inv.get("id"), "invoice_no", String.valueOf(inv.get("invoice_no")), "invoice_date", String.valueOf(inv.get("invoice_date")),
                "party", String.valueOf(inv.get("counterparty_name")), "doc_type", String.valueOf(inv.get("doc_type")),
                "taxable_paise", sign * tx, "tax_paise", sign * (ig + cg + sg + ce)));
    }
    public long tax() { return igst + cgst + sgst + cess; }

    public Map<String, Object> toMap() {
        return Map.of("key", key, "label", label, "rule", rule, "ref", ref, "taxable_paise", taxable, "igst_paise", igst, "cgst_paise", cgst,
                "sgst_paise", sgst, "cess_paise", cess, "sources", sources);
    }
}
