package com.account0.extraction;

import com.account0.common.Money;
import com.account0.company.Gstin;
import com.account0.invoice.Flag;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.*;

/**
 * Pure mapping from the model's JSON to a draft invoice + confidence flags.
 * Direction is decided deterministically from our own GSTIN; tax treatment is NOT mapped (user decides).
 */
public final class ExtractionMapper {
    private ExtractionMapper() {}
    public static final double LOW_CONFIDENCE = 0.85;

    public record Result(Map<String, Object> header, List<Map<String, Object>> lines, List<Flag> flags) {}

    static String v(JsonNode field) {
        if (field == null || field.isNull() || field.isMissingNode()) return null;
        JsonNode val = field.isObject() ? field.get("value") : field;
        if (val == null || val.isNull()) return null;
        String s = val.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }
    static double c(JsonNode field) { return field != null && field.isObject() && field.has("confidence") ? field.get("confidence").asDouble(0) : 0; }

    static Long paise(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[^0-9.\\-]", "");
        if (t.isEmpty() || t.equals("-") || t.equals(".")) return null;
        try { return Money.toPaise(new BigDecimal(t)); } catch (Exception e) { return null; }
    }

    public static Result map(JsonNode j, String ourGstin, String ourName) {
        Map<String, Object> h = new LinkedHashMap<>();
        List<Flag> flags = new ArrayList<>();
        JsonNode sup = j.path("supplier"), rec = j.path("recipient");
        String supG = norm(v(sup.get("gstin"))), recG = norm(v(rec.get("gstin")));
        String kind = v(j.get("document_kind"));
        if ("NOT_AN_INVOICE".equals(kind)) flags.add(Flag.error("document_kind", "NOT_INVOICE", "This document does not look like an invoice. Reject it, or fill the fields manually."));

        // Direction (deterministic): who holds our GSTIN?
        String direction = null; JsonNode cp = null; String cpG = null;
        if (ourGstin != null && ourGstin.equalsIgnoreCase(supG)) { direction = "SALE"; cp = rec; cpG = recG; }
        else if (ourGstin != null && ourGstin.equalsIgnoreCase(recG)) { direction = "PURCHASE"; cp = sup; cpG = supG; }
        else if (ourName != null && nameMatch(ourName, v(sup.get("name")))) { direction = "SALE"; cp = rec; cpG = recG; flags.add(Flag.warn("direction", "DIRECTION_BY_NAME", "Direction inferred from your company name (GSTIN not found on the invoice) — please check.")); }
        else if (ourName != null && nameMatch(ourName, v(rec.get("name")))) { direction = "PURCHASE"; cp = sup; cpG = supG; flags.add(Flag.warn("direction", "DIRECTION_BY_NAME", "Direction inferred from your company name (GSTIN not found on the invoice) — please check.")); }
        else { cp = sup; cpG = supG; flags.add(Flag.error("direction", "DIRECTION_UNKNOWN", ourGstin == null ? "Set your GSTIN in Company profile so Account0 can tell sales from purchases. Choose SALE/PURCHASE." : "Your GSTIN/name is not on this invoice. Choose SALE or PURCHASE.")); }
        h.put("direction", direction);
        h.put("doc_type", switch (kind == null ? "" : kind) { case "CREDIT_NOTE" -> "CREDIT_NOTE"; case "DEBIT_NOTE" -> "DEBIT_NOTE"; default -> "INVOICE"; });
        h.put("counterparty_name", v(cp.get("name")));
        h.put("counterparty_gstin", cpG);
        String country = v(cp.get("country"));
        h.put("counterparty_country", country != null ? country : (cpG != null ? "India" : null));
        conf(flags, "counterparty_name", cp.get("name"), "Counterparty name");
        if (cpG != null) conf(flags, "counterparty_gstin", cp.get("gstin"), "Counterparty GSTIN");
        if (cpG != null && !Gstin.isValid(cpG)) flags.add(Flag.error("counterparty_gstin", "GSTIN_INVALID", "Extracted GSTIN " + cpG + " fails the check digit — likely misread."));

        h.put("invoice_no", v(j.get("invoice_no"))); conf(flags, "invoice_no", j.get("invoice_no"), "Invoice number");
        h.put("invoice_date", v(j.get("invoice_date"))); conf(flags, "invoice_date", j.get("invoice_date"), "Invoice date");
        h.put("original_invoice_no", v(j.get("original_invoice_no")));
        String cur = v(j.get("currency"));
        h.put("currency", cur == null ? "INR" : cur.toUpperCase(Locale.ROOT));
        h.put("place_of_supply", posCode(v(j.get("place_of_supply")), country));
        if (v(j.get("place_of_supply")) != null && h.get("place_of_supply") == null)
            flags.add(Flag.warn("place_of_supply", "POS_UNPARSED", "Place of supply printed as '" + v(j.get("place_of_supply")) + "' — choose the state code."));

        JsonNode t = j.path("totals");
        Long taxable = paise(v(t.get("taxable_value"))), cg = paise(v(t.get("cgst"))), sg = paise(v(t.get("sgst"))), ig = paise(v(t.get("igst"))), cess = paise(v(t.get("cess"))), total = paise(v(t.get("total")));
        boolean foreign = !"INR".equals(h.get("currency"));
        if (foreign) {
            h.put("foreign_amount", v(t.get("total")));
            Long inr = paise(v(j.get("inr_total_printed")));
            flags.add(Flag.warn("fx_rate", "FOREIGN_CURRENCY", "Invoice is in " + h.get("currency") + " " + v(t.get("total")) + ". Enter the INR value at the exchange rate you will use (Rule 34 CGST Rules: RBI reference rate on the date of supply)." + (inr != null ? " The invoice prints an INR total of ₹" + Money.fmt(inr) + "." : "")));
            if (inr != null) { taxable = inr; total = inr; } else { taxable = null; total = null; }
        }
        h.put("taxable_paise", nz(taxable)); h.put("cgst_paise", nz(cg)); h.put("sgst_paise", nz(sg)); h.put("igst_paise", nz(ig)); h.put("cess_paise", nz(cess)); h.put("total_paise", nz(total));
        conf(flags, "taxable_paise", t.get("taxable_value"), "Taxable value");
        conf(flags, "total_paise", t.get("total"), "Invoice total");

        List<Map<String, Object>> lines = new ArrayList<>();
        int n = 1;
        for (JsonNode ln : j.path("lines")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", text(ln.get("description")));
            m.put("hsn_sac", text(ln.get("hsn_sac")));
            m.put("quantity", text(ln.get("quantity")));
            String rate = text(ln.get("gst_rate"));
            m.put("gst_rate", rate == null ? null : rate.replace("%", "").trim());
            if (foreign) { m.put("taxable_paise", lines.isEmpty() && j.path("lines").size() == 1 ? nz(taxable) : 0L); m.put("cgst_paise", 0L); m.put("sgst_paise", 0L); m.put("igst_paise", 0L); }
            else {
                m.put("taxable_paise", nz(paise(text(ln.get("taxable_value"))))); m.put("cgst_paise", nz(paise(text(ln.get("cgst")))));
                m.put("sgst_paise", nz(paise(text(ln.get("sgst"))))); m.put("igst_paise", nz(paise(text(ln.get("igst")))));
            }
            m.put("cess_paise", nz(paise(text(ln.get("cess")))));
            double lc = ln.path("confidence").asDouble(1);
            if (lc < LOW_CONFIDENCE) flags.add(Flag.warn("lines", "LOW_CONFIDENCE", "Line " + n + " (" + m.get("description") + ") read with low confidence " + pct(lc) + "."));
            lines.add(m); n++;
        }

        // Facts printed on the face of the invoice → INFO hints only. The user chooses the treatment.
        String rcm = v(j.get("reverse_charge_printed"));
        if ("true".equalsIgnoreCase(rcm)) flags.add(Flag.info("reverse_charge", "RCM_PRINTED", "The invoice states that tax is payable on reverse charge. Confirm the treatment yourself."));
        if ("true".equalsIgnoreCase(v(j.get("lut_or_without_payment_printed")))) flags.add(Flag.info("supply_type", "LUT_PRINTED", "The invoice mentions LUT / export without payment of IGST."));
        if (foreign && "PURCHASE".equals(direction)) flags.add(Flag.info("supply_type", "FOREIGN_VENDOR", "Foreign vendor invoice. Imports of services are usually taxed under reverse charge (Sec 5(3) IGST Act) — but you must confirm the treatment."));

        String cat = v(j.get("category_guess"));
        h.put("category", cat);
        h.put("category_suggested", cat == null ? 0 : 1);
        if (cat != null) flags.add(Flag.info("category", "CATEGORY_SUGGESTED", "Category '" + cat + "' is a suggestion (" + pct(c(j.get("category_guess"))) + ") — change it if wrong."));
        String notes = text(j.get("notes"));
        if (notes != null) { h.put("notes", "Extraction note: " + notes); flags.add(Flag.warn("notes", "MODEL_NOTE", "Extractor noted: " + notes)); }
        return new Result(h, lines, flags);
    }

    static void conf(List<Flag> flags, String field, JsonNode node, String label) {
        if (node == null || v(node) == null) return;
        double c = c(node);
        if (c < LOW_CONFIDENCE) flags.add(Flag.warn(field, "LOW_CONFIDENCE", label + " read with low confidence (" + pct(c) + "): '" + v(node) + "'. Check it against the document."));
    }
    static String pct(double c) { return Math.round(c * 100) + "%"; }
    static long nz(Long x) { return x == null ? 0 : x; }
    static String text(JsonNode n) { if (n == null || n.isNull()) return null; String s = n.isObject() ? v(n) : n.asText().trim(); return s == null || s.isEmpty() || s.equals("null") ? null : s; }
    static String norm(String g) { return g == null ? null : g.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", ""); }
    static boolean nameMatch(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.toUpperCase().replaceAll("[^A-Z0-9]", ""), y = b.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return !x.isEmpty() && (x.equals(y) || y.startsWith(x) || x.startsWith(y));
    }

    /** "29-Karnataka" → 29; "Karnataka" → 29; "Outside India"/foreign → 96. */
    static String posCode(String printed, String country) {
        if (printed == null) return country != null && !country.equalsIgnoreCase("India") ? "96" : null;
        String p = printed.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{2})\\b").matcher(p);
        if (m.find() && Gstin.STATES.containsKey(m.group(1))) return m.group(1);
        String up = p.toUpperCase(Locale.ROOT);
        if (up.contains("OUTSIDE INDIA") || up.contains("OTHER COUNTRY") || up.contains("EXPORT")) return "96";
        for (var e : Gstin.STATES.entrySet()) if (up.contains(e.getValue().toUpperCase(Locale.ROOT))) return e.getKey();
        return null;
    }
}
