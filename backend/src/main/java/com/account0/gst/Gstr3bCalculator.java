package com.account0.gst;

import com.account0.invoice.SupplyType;

import java.util.*;

/**
 * Deterministic GSTR-3B computation from CONFIRMED invoices of one tax period. Pure: no DB, no LLM.
 * Credit notes are subtracted (sign -1) in the table of the original supply.
 */
public final class Gstr3bCalculator {
    private Gstr3bCalculator() {}

    static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    /** GSTR-2B input for 4A(5)/4A(4). claimable = 2B lines of THIS 2B period that matched a purchase in the books (all heads agree) and are
     *  marked ITC-available; isd = ISD credit lines; notes = lines left out and why. Null = 2B not imported (4A(5) provisional from books). */
    public record TwoB(List<Map<String, Object>> claimable, List<Map<String, Object>> isd, List<String> notes) {}

    public static Map<String, Object> compute(List<Map<String, Object>> invoices, String ourState) { return compute(invoices, ourState, null, null); }

    /** Sec 16(4) CGST Act: ITC of an invoice must be claimed by 30 Nov after the end of its FY, so a return period of November (FY end year) or later is too late. */
    public static boolean itcTimeBarred(Object invoiceDate, String period) {
        if (invoiceDate == null || period == null) return false;
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(invoiceDate.toString());
            int fyEnd = d.getMonthValue() >= 4 ? d.getYear() + 1 : d.getYear();
            return period.compareTo(fyEnd + "-11") >= 0;
        } catch (Exception e) { return false; }
    }

    public static Map<String, Object> compute(List<Map<String, Object>> invoices, String ourState, String period, TwoB twoB) {
        Figure a = new Figure("3.1a", "3.1(a) Outward taxable supplies (other than zero rated, nil rated and exempted)",
                "Domestic sales (B2B + B2C) at taxable value, with the tax charged on them. Credit notes reduce, debit notes increase.", "Sec 9 CGST Act; Sec 5 IGST Act; Sec 34 (credit/debit notes)");
        Figure b = new Figure("3.1b", "3.1(b) Outward taxable supplies (zero rated)",
                "Exports and SEZ supplies. Under LUT no IGST is charged, so only the taxable value appears; with-payment exports carry IGST.", "Sec 16 IGST Act; Rule 96A CGST Rules (LUT)");
        Figure c = new Figure("3.1c", "3.1(c) Other outward supplies (nil rated, exempted)", "Nil-rated and exempt sales at value.", "Sec 2(47) CGST Act; Notification 12/2017-CT(R)");
        Figure d = new Figure("3.1d", "3.1(d) Inward supplies (liable to reverse charge)",
                "Purchases where YOU pay the tax: import of services from foreign vendors and notified domestic RCM services. Tax here must be paid in cash. Reported in the month of the time of supply: the earlier of the payment date and the day after 60 days from the invoice date (Sec 13(3)).", "Sec 9(3)/9(4), 13(3) CGST Act; Sec 5(3) IGST Act; Sec 49(4) (no ITC for RCM payment)");
        Figure e = new Figure("3.1e", "3.1(e) Non-GST outward supplies", "Sales outside GST (e.g. alcohol for human consumption, petroleum).", "Sec 9(2) CGST Act");

        Figure itcImpGoods = new Figure("4A1", "4A(1) Import of goods", "IGST paid at customs on imported goods (Bill of Entry). STUB: Bill-of-Entry matching not built.", "Sec 16 CGST Act; Sec 3(7) Customs Tariff Act");
        Figure itcImpSvc = new Figure("4A2", "4A(2) Import of services", "The RCM IGST you paid on imported services is available as credit in the same month, if the service is used for business.", "Sec 16(1) CGST Act; Sec 5(3) IGST Act");
        Figure itcRcm = new Figure("4A3", "4A(3) Inward supplies liable to reverse charge (other than 1 & 2)", "Credit of RCM tax paid on domestic reverse-charge purchases.", "Sec 16(1) CGST Act");
        Figure itcIsd = new Figure("4A4", "4A(4) Inward supplies from ISD", "Credit distributed to you by an Input Service Distributor, as shown in your GSTR-2B.", "Sec 20 CGST Act; Rule 39");
        Figure itcOther = new Figure("4A5", "4A(5) All other ITC",
                twoB == null ? "GST charged by registered suppliers on forward-charge purchases, taken from your books because GSTR-2B for this period is not imported."
                             : "GST on forward-charge purchases as shown in THIS period's GSTR-2B, for lines that match a purchase in your books on every tax head. Invoices missing from 2B, mismatched lines and lines marked 'ITC not available' are left out (see notes).",
                "Sec 16(2)(aa), 16(4) CGST Act; Rule 36(4)");
        if (twoB == null) { itcOther.status = "PROVISIONAL"; itcOther.caveat = "Provisional — GSTR-2B not imported for this period. ITC is claimable only for invoices in your GSTR-2B (Sec 16(2)(aa)); import it on the ITC page to finalise 4A(5)."; }
        Figure rev17_5 = new Figure("4B1", "4B(1) ITC reversed — Rules 38, 42, 43 & Sec 17(5)", "Purchases you marked as not ITC-eligible (blocked credits under Sec 17(5), personal use). Reported in 4A and reversed here.", "Sec 17(5) CGST Act; CBIC Circular 170/02/2022-GST");
        Figure exemptInInter = new Figure("5-inter", "5 Exempt, nil-rated and non-GST inward supplies — inter-state", "Value of inward supplies that carry no GST (nil/exempt/non-GST, composition suppliers) from suppliers in another state.", "GSTR-3B Table 5");
        Figure exemptInIntra = new Figure("5-intra", "5 Exempt, nil-rated and non-GST inward supplies — intra-state", "Value of inward supplies that carry no GST from suppliers in your state (or with no GSTIN, assumed local).", "GSTR-3B Table 5");
        Map<String, Figure> table32 = new TreeMap<>();
        List<String> notes = new ArrayList<>(), errors = new ArrayList<>();
        if (ourState == null) errors.add("Company GSTIN is not set: intra-/inter-state cannot be decided, so Table 3.2 and the Table 5 split are not computed. Set the GSTIN in Company profile.");

        for (Map<String, Object> inv : invoices) {
            SupplyType t = SupplyType.valueOf((String) inv.get("supply_type"));
            long sign = "CREDIT_NOTE".equals(inv.get("doc_type")) ? -1 : 1;
            long tx = l(inv.get("taxable_paise")), ig = l(inv.get("igst_paise")), cg = l(inv.get("cgst_paise")), sg = l(inv.get("sgst_paise")), ce = l(inv.get("cess_paise"));
            boolean itc = l(inv.get("itc_eligible")) == 1;
            String pos = (String) inv.get("place_of_supply");
            boolean inter = ourState != null && pos != null && !pos.equals(ourState);
            switch (t) {
                case B2B, B2C -> {
                    a.add(inv, sign, tx, ig, cg, sg, ce);
                    String kind = t == SupplyType.B2C ? "UNREG" : Objects.toString(inv.get("buyer_kind"), "");
                    if (inter && (kind.equals("UNREG") || kind.equals("COMPOSITION") || kind.equals("UIN"))) {
                        String who = kind.equals("UNREG") ? "unregistered persons" : kind.equals("COMPOSITION") ? "composition taxable persons" : "UIN holders";
                        String k = (kind.equals("UNREG") ? "3.2-" : kind.equals("COMPOSITION") ? "3.2C-" : "3.2U-") + pos;
                        Figure f = table32.computeIfAbsent(k, x -> new Figure(k, "3.2 Inter-state supplies to " + who + " — POS " + pos,
                                "Inter-state sales to " + who + " shown again by place of supply (already included in 3.1(a)).", "GSTR-3B Table 3.2; Sec 10 IGST Act"));
                        f.add(inv, sign, tx, ig, 0, 0, 0);
                    }
                }
                case EXPORT_LUT, EXPORT_PAID, SEZ_LUT, SEZ_PAID -> b.add(inv, sign, tx, ig, cg, sg, ce);
                case NIL_EXEMPT_OUT -> c.add(inv, sign, tx, 0, 0, 0, 0);
                case NON_GST_OUT -> e.add(inv, sign, tx, 0, 0, 0, 0);
                case IMPORT_SERVICE_RCM, DOMESTIC_RCM -> {
                    d.add(inv, sign, tx, ig, cg, sg, ce);
                    Figure itcF = t == SupplyType.IMPORT_SERVICE_RCM ? itcImpSvc : itcRcm;
                    itcF.add(inv, sign, 0, ig, cg, sg, ce);
                    if (!itc) rev17_5.add(inv, sign, 0, ig, cg, sg, ce);
                }
                case DOMESTIC -> {
                    if (twoB != null) break; // 4A(5) comes from GSTR-2B below
                    if (sign > 0 && itcTimeBarred(inv.get("invoice_date"), period)) {
                        notes.add("Invoice " + inv.get("invoice_no") + " (" + inv.get("counterparty_name") + ", " + inv.get("invoice_date") + "): ITC is time-barred for this period (Sec 16(4): claim by 30 November after the end of the invoice's FY) — not included in 4A(5).");
                        break;
                    }
                    itcOther.add(inv, sign, 0, ig, cg, sg, ce);
                    if (!itc) rev17_5.add(inv, sign, 0, ig, cg, sg, ce);
                }
                case IMPORT_GOODS -> {
                    itcImpGoods.add(inv, sign, 0, ig, 0, 0, ce);
                    if (!itc) rev17_5.add(inv, sign, 0, ig, 0, 0, ce);
                }
                case NIL_EXEMPT_IN -> {
                    String supState = com.account0.company.Gstin.stateCode((String) inv.get("counterparty_gstin"));
                    if (ourState == null) break;
                    boolean interIn = supState != null && !supState.equals(ourState);
                    (interIn ? exemptInInter : exemptInIntra).add(inv, sign, tx, 0, 0, 0, 0);
                }
                case UNREGISTERED -> notes.add("Invoice " + inv.get("invoice_no") + " (" + inv.get("counterparty_name") + ") is a purchase from an unregistered supplier: no GST, not reported in 3B.");
            }
        }
        if (twoB != null) {
            for (Map<String, Object> ln : twoB.claimable()) {
                long sign = "CREDIT_NOTE".equals(ln.get("doc_type")) ? -1 : 1;
                if (sign > 0 && itcTimeBarred(ln.get("invoice_date"), period)) {
                    notes.add("2B line " + ln.get("invoice_no") + " (" + ln.get("counterparty_name") + ", " + ln.get("invoice_date") + "): time-barred under Sec 16(4) — not included in 4A(5).");
                    continue;
                }
                itcOther.add(ln, sign, 0, l(ln.get("igst_paise")), l(ln.get("cgst_paise")), l(ln.get("sgst_paise")), l(ln.get("cess_paise")));
                if (ln.get("itc_eligible") != null && l(ln.get("itc_eligible")) == 0) rev17_5.add(ln, sign, 0, l(ln.get("igst_paise")), l(ln.get("cgst_paise")), l(ln.get("sgst_paise")), l(ln.get("cess_paise")));
            }
            for (Map<String, Object> ln : twoB.isd()) {
                long sign = "CREDIT_NOTE".equals(ln.get("doc_type")) ? -1 : 1;
                itcIsd.add(ln, sign, 0, l(ln.get("igst_paise")), l(ln.get("cgst_paise")), l(ln.get("sgst_paise")), l(ln.get("cess_paise")));
            }
            notes.addAll(twoB.notes());
        }
        if (c.taxable > 0)
            notes.add("You have exempt/nil-rated sales this period (3.1(c)). Common input credit used for both taxable and exempt supplies must be partly reversed under Rules 42/43 (4B(1)) — Account0 does not compute this. Verify with your CA. (Exports are zero-rated and need no reversal.)");

        Figure itcTotal = sum("4A", "4A Total ITC available", "Sum of 4A(1) to 4A(5).", "Sec 16 CGST Act", itcImpGoods, itcImpSvc, itcRcm, itcIsd, itcOther);
        if (twoB == null) { itcTotal.status = "PROVISIONAL"; itcTotal.caveat = itcOther.caveat; }
        Figure net = new Figure("4C", "4C Net ITC available (4A − 4B)", "Credit you can use this month to pay output tax.", "Sec 49 CGST Act");
        net.igst = itcTotal.igst - rev17_5.igst; net.cgst = itcTotal.cgst - rev17_5.cgst; net.sgst = itcTotal.sgst - rev17_5.sgst; net.cess = itcTotal.cess - rev17_5.cess;
        if (twoB == null) { net.status = "PROVISIONAL"; net.caveat = itcOther.caveat; }

        // 6.1 Payment of tax
        long[] fwd = {a.igst + b.igst, a.cgst + b.cgst, a.sgst + b.sgst, a.cess + b.cess};
        long[] rcm = {d.igst, d.cgst, d.sgst, d.cess};
        SetOff so = SetOff.apply(fwd, new long[]{net.igst, net.cgst, net.sgst, net.cess});

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table31", List.of(a.toMap(), b.toMap(), c.toMap(), d.toMap(), e.toMap()));
        out.put("table32", table32.values().stream().map(Figure::toMap).toList());
        out.put("table4", List.of(itcImpGoods.toMap(), itcImpSvc.toMap(), itcRcm.toMap(), itcIsd.toMap(), itcOther.toMap(), itcTotal.toMap(), rev17_5.toMap(), net.toMap()));
        out.put("table5", List.of(exemptInInter.toMap(), exemptInIntra.toMap()));
        out.put("itcProvisional", twoB == null);
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("rule", "Output tax (other than reverse charge) is first set off against ITC. IGST credit must be used up first (Rule 88A): against IGST, then the rest against CGST and SGST in any order or proportion — Account0 sends it to the head where your own CGST/SGST credit falls short, so the least cash is paid (Circular 98/17/2019-GST). Then CGST credit pays CGST, then IGST; SGST credit pays SGST, then IGST. CGST and SGST credit never cross-utilise. The balance and ALL reverse-charge tax are paid in cash from the electronic cash ledger.");
        pay.put("ref", "Sec 49(5), 49A, 49(4) CGST Act; Rule 88A CGST Rules; Circular 98/17/2019-GST");
        pay.put("heads", so.rows(rcm));
        pay.put("cashTotalPaise", so.cashTotal() + rcm[0] + rcm[1] + rcm[2] + rcm[3]);
        pay.put("itcCarriedForwardPaise", Map.of("igst", so.leftover[0], "cgst", so.leftover[1], "sgst", so.leftover[2], "cess", so.leftover[3]));
        out.put("payment61", pay);
        out.put("notes", notes);
        out.put("errors", errors);
        return out;
    }

    static Figure sum(String key, String label, String rule, String ref, Figure... fs) {
        Figure t = new Figure(key, label, rule, ref);
        for (Figure f : fs) { t.taxable += f.taxable; t.igst += f.igst; t.cgst += f.cgst; t.sgst += f.sgst; t.cess += f.cess; t.sources.addAll(f.sources); }
        return t;
    }

    /** ITC utilisation per Sec 49/49A + Rule 88A. Index 0=IGST,1=CGST,2=SGST,3=Cess. */
    public static final class SetOff {
        final long[] liability = new long[4], cash = new long[4], leftover = new long[4];
        final long[][] paidBy = new long[4][4]; // [liabilityHead][creditHead]

        public static SetOff apply(long[] liab, long[] credit) {
            SetOff s = new SetOff();
            long[] rem = liab.clone(), cr = new long[4];
            for (int i = 0; i < 4; i++) { s.liability[i] = Math.max(0, liab[i]); rem[i] = Math.max(0, liab[i]); cr[i] = Math.max(0, credit[i]); }
            // Rule 88A: IGST credit first, fully: IGST, then CGST/SGST in any proportion (Circular 98/17/2019).
            s.use(rem, cr, 0, 0);
            // send residual IGST credit where own CGST/SGST credit cannot cover the liability (minimises cash) ...
            long needC = Math.max(0, rem[1] - cr[1]), needS = Math.max(0, rem[2] - cr[2]);
            s.useUpTo(rem, cr, 0, 1, needC); s.useUpTo(rem, cr, 0, 2, needS);
            // ... and any IGST credit still left must be exhausted before CGST/SGST credit is used
            s.use(rem, cr, 0, 1); s.use(rem, cr, 0, 2);
            // CGST credit: CGST then IGST ; SGST credit: SGST then IGST
            s.use(rem, cr, 1, 1); s.use(rem, cr, 1, 0);
            s.use(rem, cr, 2, 2); s.use(rem, cr, 2, 0);
            s.use(rem, cr, 3, 3);
            for (int i = 0; i < 4; i++) { s.cash[i] = rem[i]; s.leftover[i] = cr[i]; }
            return s;
        }
        void use(long[] rem, long[] cr, int credit, int head) {
            long x = Math.min(rem[head], cr[credit]);
            rem[head] -= x; cr[credit] -= x; paidBy[head][credit] += x;
        }
        void useUpTo(long[] rem, long[] cr, int credit, int head, long cap) {
            long x = Math.min(cap, Math.min(rem[head], cr[credit]));
            rem[head] -= x; cr[credit] -= x; paidBy[head][credit] += x;
        }
        long cashTotal() { return cash[0] + cash[1] + cash[2] + cash[3]; }
        public long cash(int h) { return cash[h]; }
        public long paidBy(int head, int credit) { return paidBy[head][credit]; }

        List<Map<String, Object>> rows(long[] rcm) {
            String[] names = {"IGST", "CGST", "SGST", "Cess"};
            List<Map<String, Object>> r = new ArrayList<>();
            for (int h = 0; h < 4; h++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("head", names[h]); m.put("liabilityPaise", liability[h]);
                m.put("paidByIgstItcPaise", paidBy[h][0]); m.put("paidByCgstItcPaise", paidBy[h][1]); m.put("paidBySgstItcPaise", paidBy[h][2]); m.put("paidByCessItcPaise", paidBy[h][3]);
                m.put("cashPaise", cash[h]); m.put("rcmLiabilityPaise", rcm[h]); m.put("rcmCashPaise", rcm[h]);
                r.add(m);
            }
            return r;
        }
    }
}
