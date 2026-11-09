package com.account0.invoice;

import java.util.List;

/**
 * Tax treatment of an invoice. ALWAYS chosen/confirmed by the user (never inferred by the LLM).
 * The "rule" text is the plain-language explanation shown in the UI.
 */
public enum SupplyType {
    // Outward (SALE)
    B2B("SALE", "Domestic sale to a GST-registered buyer", "Taxable outward supply; reported in GSTR-1 Table 4 (B2B) and 3B 3.1(a)."),
    B2C("SALE", "Domestic sale to an unregistered buyer / consumer", "Taxable outward supply; GSTR-1 Table 5 (B2CL, inter-state > ₹1 lakh) or 7 (B2CS); 3B 3.1(a)."),
    EXPORT_LUT("SALE", "Export without payment of IGST under LUT", "Zero-rated supply (Sec 16 IGST Act); GSTR-1 Table 6A (WOPAY); 3B 3.1(b) with zero tax."),
    EXPORT_PAID("SALE", "Export with payment of IGST (refund route)", "Zero-rated supply; GSTR-1 Table 6A (WPAY); 3B 3.1(b) with IGST."),
    SEZ_LUT("SALE", "Supply to SEZ without payment under LUT", "Zero-rated; GSTR-1 Table 6B (SEWOP); 3B 3.1(b)."),
    SEZ_PAID("SALE", "Supply to SEZ with payment of IGST", "Zero-rated; GSTR-1 Table 6B (SEWP); 3B 3.1(b)."),
    NIL_EXEMPT_OUT("SALE", "Nil-rated / exempt outward supply", "GSTR-1 Table 8; 3B 3.1(c)."),
    NON_GST_OUT("SALE", "Non-GST outward supply", "GSTR-1 Table 8; 3B 3.1(e)."),
    // Inward (PURCHASE)
    DOMESTIC("PURCHASE", "Purchase from a GST-registered supplier (forward charge)", "Supplier charges GST; ITC (if eligible) in 3B 4A(5) — subject to GSTR-2B (Sec 16(2)(aa))."),
    DOMESTIC_RCM("PURCHASE", "Purchase from a registered supplier under reverse charge (Sec 9(3), e.g. GTA, legal)", "You pay the tax in cash: 3B 3.1(d); ITC in 4A(3)."),
    IMPORT_SERVICE_RCM("PURCHASE", "Import of services from a foreign vendor (reverse charge)", "IGST payable by you under Sec 5(3) IGST Act: 3B 3.1(d); ITC in 4A(2)."),
    UNREGISTERED("PURCHASE", "Purchase from an unregistered supplier (no GST)", "No GST, no ITC. Reported in 3B 5 only if exempt/nil/non-GST inward."),
    NIL_EXEMPT_IN("PURCHASE", "Nil-rated / exempt / non-GST inward supply", "3B Table 5 (inward exempt, nil, non-GST)."),
    IMPORT_GOODS("PURCHASE", "Import of goods (Bill of Entry, IGST paid at customs)", "ITC in 3B 4A(1) — STUB: Bill of Entry matching not built.");

    public final String direction, label, rule;
    SupplyType(String direction, String label, String rule) { this.direction = direction; this.label = label; this.rule = rule; }

    public boolean isRcm() { return this == DOMESTIC_RCM || this == IMPORT_SERVICE_RCM; }
    public boolean zeroTaxOnInvoice() { return this == EXPORT_LUT || this == SEZ_LUT || this == NIL_EXEMPT_OUT || this == NON_GST_OUT || this == UNREGISTERED || this == NIL_EXEMPT_IN || isRcm(); }

    public static final List<String> RATES = List.of("0", "0.1", "0.25", "1.5", "3", "5", "12", "18", "28", "40");
}
