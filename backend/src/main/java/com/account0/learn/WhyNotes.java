package com.account0.learn;

import java.util.*;

/** Short inline "why" notes shown next to figures; each cites corpus entries. Deterministic text, no LLM. */
public final class WhyNotes {
    private WhyNotes() {}
    public record Note(String title, String text, List<String> cites) {}
    public static final Map<String, Note> ALL = new LinkedHashMap<>();
    private static void n(String k, String title, String text, String... cites) { ALL.put(k, new Note(title, text, List.of(cites))); }
    static {
        n("3.1a", "3.1(a) Outward taxable supplies", "Sales on which you charged GST (B2B, B2C, and exports where IGST was paid). The taxable value and the CGST/SGST/IGST you charged must agree with your GSTR-1.", "cgst-9", "cgst-37", "cgst-39");
        n("3.1b", "3.1(b) Zero-rated supplies", "Exports of services under LUT (no tax charged) and supplies to SEZs. Taxable value only; tax is nil unless you paid IGST on exports.", "igst-16", "igst-2-6", "cgst-rule-96a");
        n("3.1c", "3.1(c) Nil-rated / exempt", "Supplies that carry 0% or are exempt. Reported for value only.", "cgst-39");
        n("3.1d", "3.1(d) Inward supplies liable to reverse charge", "Purchases where YOU pay the GST - e.g. SaaS or consulting from a foreign vendor. You work out the IGST, pay it in cash, and later claim it as ITC in 4(A)(2).", "igst-5-3", "cgst-9-3", "term-rcm");
        n("3.1e", "3.1(e) Non-GST outward supplies", "Supplies outside GST (e.g. petroleum, alcohol for human consumption). Rare for a software company.", "cgst-39");
        n("4A1", "4(A)(1) Import of goods", "ITC on IGST paid at customs on goods you import. Needs the Bill of Entry.", "cgst-16");
        n("4A2", "4(A)(2) Import of services", "ITC on IGST you paid under reverse charge on imported services. Appears in your books only after you have paid the RCM tax.", "igst-5-3", "cgst-16");
        n("4A3", "4(A)(3) Inward supplies liable to reverse charge (other than imports)", "ITC on RCM tax paid on domestic reverse-charge purchases, such as legal services from an advocate.", "cgst-9-3", "cgst-16");
        n("4A5", "4(A)(5) All other ITC", "ITC on ordinary domestic purchases. Claim only what appears in GSTR-2B and is not blocked.", "cgst-16", "cgst-16-2-aa", "gstr2b");
        n("4B1", "4(B)(1) ITC reversed", "Credit you must give back: blocked items under Sec 17(5), and Rules 38, 42 and 43 reversals.", "cgst-17-5", "cgst-16");
        n("4C", "4(C) Net ITC available", "Total eligible ITC (A) minus reversals (B). This is what can pay your output tax.", "cgst-49");
        n("6.1", "6.1 Payment of tax", "Shows how liability is paid: ITC in the legal order first (IGST credit first), cash for the rest. RCM tax is always cash.", "cgst-49", "cgst-rule-88a", "term-setoff");
        n("RCM", "Reverse charge", "You pay the GST because the vendor is abroad or in a notified category.", "term-rcm", "igst-5-3");
        n("LUT", "Letter of Undertaking", "Needed every financial year to export services without paying IGST.", "term-lut", "cgst-rule-96a");
        n("2B", "GSTR-2B", "Your auto-drafted ITC statement. ITC outside it cannot be claimed.", "gstr2b", "cgst-16-2-aa");
        n("TDS", "TDS on payments", "You deduct tax when paying certain vendors above thresholds. You choose the section; Account0 does the arithmetic.", "it-194j", "it-194c", "it-195", "cbdt-23-2017");
        n("TDS_194J", "194J - professional / technical fees", "10% for professional fees, 2% for technical services, over ₹50,000 a year per vendor.", "it-194j");
        n("SALARY", "Salary TDS", "Estimated annual tax divided over the remaining months, recalculated as pay changes.", "it-192", "it-115bac", "it-87a");
        n("CALENDAR", "Due dates", "Statutory dates; late fee and interest accrue automatically on GST and TDS delays.", "cgst-47", "cgst-50", "it-201-1a");
    }
}
