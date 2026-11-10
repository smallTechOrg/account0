package com.account0.invoice;

import com.account0.company.Gstin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deterministic validations. Pure functions — no DB, no LLM. */
public final class InvoiceChecks {
    private InvoiceChecks() {}
    static final long TOLERANCE_PAISE = 100; // ₹1 rounding tolerance

    public static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    static String s(Object o) { return o == null ? null : o.toString(); }

    /** Facts-only checks on the numbers & identifiers. */
    public static List<Flag> structural(Map<String, Object> inv, List<Map<String, Object>> lines, String ourGstin, LocalDate today) {
        List<Flag> f = new ArrayList<>();
        String cp = s(inv.get("counterparty_gstin"));
        if (cp != null && !cp.isBlank() && !Gstin.isValid(cp)) f.add(Flag.error("counterparty_gstin", "GSTIN_INVALID", "GSTIN " + cp + " fails format/check-digit validation — re-read it from the document."));
        if (s(inv.get("invoice_no")) == null || s(inv.get("invoice_no")).isBlank()) f.add(Flag.error("invoice_no", "MISSING", "Invoice number is missing."));
        String d = s(inv.get("invoice_date"));
        if (d == null) f.add(Flag.error("invoice_date", "MISSING", "Invoice date is missing."));
        else {
            try {
                LocalDate ld = LocalDate.parse(d);
                if (ld.isAfter(today.plusDays(1))) f.add(Flag.error("invoice_date", "FUTURE", "Invoice date " + d + " is in the future."));
                if (ld.isBefore(today.minusYears(3))) f.add(Flag.warn("invoice_date", "OLD", "Invoice date " + d + " is over 3 years old."));
            } catch (Exception e) { f.add(Flag.error("invoice_date", "BAD_DATE", "Invoice date must be YYYY-MM-DD.")); }
        }
        long taxable = l(inv.get("taxable_paise")), cg = l(inv.get("cgst_paise")), sg = l(inv.get("sgst_paise")), ig = l(inv.get("igst_paise")), cess = l(inv.get("cess_paise")), total = l(inv.get("total_paise"));
        String st = s(inv.get("supply_type"));
        boolean rcm = st != null && SupplyType.valueOf(st).isRcm();
        // For RCM invoices the tax columns hold OUR liability, and total = amount payable to vendor (= taxable).
        long expectTotal = rcm ? taxable + cess : taxable + cg + sg + ig + cess;
        if (Math.abs(expectTotal - total) > TOLERANCE_PAISE)
            f.add(Flag.error("total_paise", "TOTAL_MISMATCH", String.format("Taxable + taxes = %s but invoice total is %s.", rs(expectTotal), rs(total))));
        if (Math.abs(cg - sg) > 1) f.add(Flag.error("cgst_paise", "CGST_NE_SGST", "CGST and SGST must be equal (1 paisa rounding allowed)."));
        if ((cg > 0 || sg > 0) && ig > 0) f.add(Flag.error("igst_paise", "MIXED_HEADS", "An invoice cannot have both CGST/SGST and IGST."));
        if (!lines.isEmpty()) {
            long lt = lines.stream().mapToLong(x -> l(x.get("taxable_paise"))).sum();
            long ltax = lines.stream().mapToLong(x -> l(x.get("cgst_paise")) + l(x.get("sgst_paise")) + l(x.get("igst_paise"))).sum();
            if (Math.abs(lt - taxable) > TOLERANCE_PAISE) f.add(Flag.error("lines", "LINES_TAXABLE_MISMATCH", String.format("Line taxable values sum to %s, header says %s.", rs(lt), rs(taxable))));
            if (!rcm && Math.abs(ltax - (cg + sg + ig)) > TOLERANCE_PAISE) f.add(Flag.error("lines", "LINES_TAX_MISMATCH", String.format("Line taxes sum to %s, header says %s.", rs(ltax), rs(cg + sg + ig))));
            for (Map<String, Object> x : lines) {
                String rate = s(x.get("gst_rate"));
                if (rate != null && !SupplyType.RATES.contains(stripZeros(rate)))
                    f.add(Flag.error("lines", "RATE_NOT_SLAB", "Line " + x.get("line_no") + ": rate " + rate + "% is not a GST slab."));
                else if (rate != null && SupplyType.PRE_GST2_RATES.contains(stripZeros(rate)) && d != null && d.compareTo(SupplyType.GST2_FROM) >= 0)
                    f.add(Flag.warn("lines", "RATE_PRE_GST2", "Line " + x.get("line_no") + ": " + stripZeros(rate) + "% was removed by the GST 2.0 rate rationalisation from 22 Sep 2025 (slabs now 5/18/40). Check the rate for this " + d + " invoice — usually 5% or 18%."));
                long lx = l(x.get("taxable_paise")), tx = l(x.get("cgst_paise")) + l(x.get("sgst_paise")) + l(x.get("igst_paise"));
                if (rate != null && lx > 0 && !rcm && tx > 0) {
                    long expect = new BigDecimal(lx).multiply(new BigDecimal(rate)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
                    if (Math.abs(expect - tx) > TOLERANCE_PAISE) f.add(Flag.warn("lines", "RATE_TAX_MISMATCH", String.format("Line %s: %s%% of %s is %s but tax shown is %s.", x.get("line_no"), rate, rs(lx), rs(expect), rs(tx))));
                }
            }
        }
        // intra- vs inter-state consistency (Sec 7/8 IGST Act): supplier state vs place of supply
        String pos = s(inv.get("place_of_supply"));
        String dir = s(inv.get("direction"));
        String supplierState = "SALE".equals(dir) ? Gstin.stateCode(ourGstin) : Gstin.stateCode(cp);
        if (pos != null && supplierState != null && !rcm) {
            boolean intra = pos.equals(supplierState);
            if (intra && ig > 0) f.add(Flag.warn("igst_paise", "IGST_ON_INTRA", "Place of supply " + pos + " = supplier state, so CGST+SGST is expected, but IGST is charged."));
            if (!intra && (cg > 0 || sg > 0)) f.add(Flag.warn("cgst_paise", "CGST_ON_INTER", "Place of supply " + pos + " ≠ supplier state " + supplierState + ", so IGST is expected, but CGST+SGST is charged."));
        }
        if (ourGstin != null && cp != null && cp.equalsIgnoreCase(ourGstin)) f.add(Flag.error("counterparty_gstin", "SELF_GSTIN", "Counterparty GSTIN equals your own GSTIN."));
        return f;
    }

    /** Requirements to confirm (tax treatment chosen by the user, consistent with the numbers). */
    public static List<Flag> forConfirm(Map<String, Object> inv) {
        List<Flag> f = new ArrayList<>();
        String dir = s(inv.get("direction"));
        if (dir == null) f.add(Flag.error("direction", "MISSING", "Choose whether this is a SALE or a PURCHASE."));
        String st = s(inv.get("supply_type"));
        if (st == null) { f.add(Flag.error("supply_type", "TREATMENT_UNCONFIRMED", "Choose the supply type (tax treatment). Account0 never guesses this.")); return f; }
        SupplyType t = SupplyType.valueOf(st);
        if (dir != null && !t.direction.equals(dir)) f.add(Flag.error("supply_type", "DIRECTION_MISMATCH", t.label + " is a " + t.direction + " type, but the invoice is a " + dir + "."));
        if (inv.get("reverse_charge") == null) f.add(Flag.error("reverse_charge", "TREATMENT_UNCONFIRMED", "Confirm whether reverse charge applies."));
        else if ((l(inv.get("reverse_charge")) == 1) != t.isRcm()) f.add(Flag.error("reverse_charge", "RCM_INCONSISTENT", "Reverse charge flag does not match the supply type " + t.name() + "."));
        if ("PURCHASE".equals(dir) && inv.get("itc_eligible") == null) f.add(Flag.error("itc_eligible", "TREATMENT_UNCONFIRMED", "Confirm whether input tax credit is eligible (Sec 16/17(5) CGST Act)."));
        long tax = l(inv.get("cgst_paise")) + l(inv.get("sgst_paise")) + l(inv.get("igst_paise"));
        if ((t == SupplyType.EXPORT_LUT || t == SupplyType.SEZ_LUT || t == SupplyType.UNREGISTERED || t == SupplyType.NIL_EXEMPT_IN || t == SupplyType.NIL_EXEMPT_OUT || t == SupplyType.NON_GST_OUT) && tax != 0)
            f.add(Flag.error("igst_paise", "TAX_ON_ZERO_TYPE", t.label + " should carry no GST on the invoice, but tax of " + rs(tax) + " is entered."));
        if (t == SupplyType.B2B && (inv.get("counterparty_gstin") == null)) f.add(Flag.error("counterparty_gstin", "B2B_NO_GSTIN", "B2B sale needs the buyer's GSTIN."));
        if (t == SupplyType.DOMESTIC && inv.get("counterparty_gstin") == null) f.add(Flag.error("counterparty_gstin", "NO_SUPPLIER_GSTIN", "Forward-charge purchase from a registered supplier needs the supplier GSTIN (else choose UNREGISTERED)."));
        if ((t == SupplyType.EXPORT_LUT || t == SupplyType.EXPORT_PAID) && inv.get("place_of_supply") != null && !"96".equals(inv.get("place_of_supply")))
            f.add(Flag.warn("place_of_supply", "EXPORT_POS", "Exports normally have place of supply 96 (Other Country)."));
        if (t.isRcm() && tax == 0) f.add(Flag.error("igst_paise", "RCM_TAX_MISSING", "Reverse-charge tax is zero. Enter the GST rate on the lines and click 'Compute RCM tax'."));
        if (t.isRcm() && "PURCHASE".equals(dir) && s(inv.get("rcm_paid_on")) == null && s(inv.get("rcm_bank_paid_on")) == null && inv.get("invoice_date") != null) {
            String tos;
            try { tos = LocalDate.parse(s(inv.get("invoice_date"))).plusDays(61).toString(); } catch (Exception e) { tos = "invoice date + 61 days"; }
            f.add(Flag.warn("rcm_paid_on", "RCM_TOS_UNPAID", "Reverse charge: no payment date given (and none matched from the bank). Time of supply is the earlier of payment and the day after 60 days from the invoice date (Sec 13(3)), so the tax is due in the 3B for the month of " + tos + " unless you enter the date you paid."));
        }
        if (t.isRcm() && inv.get("currency") != null && !"INR".equalsIgnoreCase(s(inv.get("currency"))))
            f.add(Flag.info("fx_rate", "RCM_FX_RULE34", "Foreign-currency RCM: the taxable value uses the exchange rate on the time-of-supply date (Rule 34 CGST Rules; Sec 14 Customs Act rate). Check the rate entered."));
        if (inv.get("category") == null) f.add(Flag.warn("category", "NO_CATEGORY", "No category / ledger head chosen."));
        return f;
    }

    /** Keeps digits and one decimal point ("18 %", "18\u00a0%" -> "18"); null when nothing numeric is left. Prevents unparseable rates (from LLM text or typing) from crashing totals. */
    public static String cleanRate(String r) {
        if (r == null) return null;
        String c = r.replaceAll("[^0-9.]", "");
        return c.isEmpty() || !c.matches("\\d+(\\.\\d+)?") ? null : c;
    }
    static String stripZeros(String r) { try { return new BigDecimal(r).stripTrailingZeros().toPlainString(); } catch (Exception e) { return r; } }
    static String rs(long p) { return "₹" + BigDecimal.valueOf(p, 2).toPlainString(); }
}
