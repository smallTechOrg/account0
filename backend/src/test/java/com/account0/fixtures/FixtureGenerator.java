package com.account0.fixtures;

import com.account0.company.Gstin;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates realistic FAKE Indian GST invoices for tests/gates. All parties are fictitious;
 * GSTINs are synthetic but carry a valid check digit.
 * Run: cd backend && ./gradlew generateFixtures
 */
public class FixtureGenerator {
    public static final String OUR_NAME = "Acme Cloudworks Private Limited";
    public static final String OUR_GSTIN = withCheck("29AAKCA1234B1Z");
    public static final String BUYER_GSTIN = withCheck("29AABCB5678C1Z");
    public static final String VENDOR_GSTIN = withCheck("27AADCN4321D1Z");

    static String withCheck(String first14) { return first14 + Gstin.checkChar(first14); }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "../fixtures/invoices");
        Files.createDirectories(dir);
        saleB2b(dir.resolve("01-sale-b2b-bluepeak.pdf"));
        exportLut(dir.resolve("02-export-lut-northwind.pdf"));
        purchaseDomestic(dir.resolve("03-purchase-nimbus-hosting.pdf"));
        foreignVendor(dir.resolve("04-foreign-atlas-analytics.pdf"));
        Path ret = dir.resolveSibling("returns"); Files.createDirectories(ret);
        filed3b(ret.resolve("gstr3b-2026-10-filed.pdf"));
        Files.writeString(dir.resolve("expected.json"), """
                {
                  "company": {"legal_name": "%s", "gstin": "%s", "cin": "U72900KA2021PTC145678", "tan": "BLRA12345B"},
                  "invoices": [
                    {"file": "01-sale-b2b-bluepeak.pdf", "invoice_no": "ACW/25-26/041", "direction": "SALE", "counterparty_gstin": "%s", "taxable_paise": 12000000, "cgst_paise": 1080000, "sgst_paise": 1080000, "igst_paise": 0, "total_paise": 14160000, "supply_type": "B2B", "reverse_charge": 0},
                    {"file": "02-export-lut-northwind.pdf", "invoice_no": "ACW/25-26/042", "direction": "SALE", "counterparty_gstin": null, "taxable_paise": 33600000, "total_paise": 33600000, "supply_type": "EXPORT_LUT", "reverse_charge": 0},
                    {"file": "03-purchase-nimbus-hosting.pdf", "invoice_no": "NHS/2026/1187", "direction": "PURCHASE", "counterparty_gstin": "%s", "taxable_paise": 5000000, "igst_paise": 900000, "total_paise": 5900000, "supply_type": "DOMESTIC", "reverse_charge": 0, "itc_eligible": 1},
                    {"file": "04-foreign-atlas-analytics.pdf", "invoice_no": "INV-2026-10-0091", "direction": "PURCHASE", "counterparty_gstin": null, "foreign_amount": "1200.00", "inr_taxable_paise": 10080000, "supply_type": "IMPORT_SERVICE_RCM", "reverse_charge": 1, "itc_eligible": 1, "gst_rate": "18"}
                  ]
                }
                """.formatted(OUR_NAME, OUR_GSTIN, BUYER_GSTIN, VENDOR_GSTIN));
        System.out.println("fixtures written to " + dir.toAbsolutePath() + " our GSTIN " + OUR_GSTIN);
    }

    // ---------- invoices ----------
    static void saleB2b(Path out) throws Exception {
        Doc d = new Doc();
        d.title("TAX INVOICE");
        d.left(OUR_NAME, true); d.left("No. 42, 3rd Floor, Indiranagar 100 Ft Road, Bengaluru, Karnataka 560038");
        d.left("GSTIN: " + OUR_GSTIN + "   PAN: AAKCA1234B   CIN: U72900KA2021PTC145678"); d.gap();
        d.kv("Invoice No:", "ACW/25-26/041"); d.kv("Invoice Date:", "05-Oct-2026"); d.kv("Place of Supply:", "29-Karnataka"); d.kv("Reverse Charge:", "No"); d.gap();
        d.left("Bill To:", true); d.left("Bluepeak Retail Private Limited"); d.left("88 MG Road, Bengaluru, Karnataka 560001"); d.left("GSTIN: " + BUYER_GSTIN); d.gap();
        d.table(new String[]{"#", "Description", "SAC", "Qty", "Rate", "Taxable", "CGST 9%", "SGST 9%"},
                new String[][]{{"1", "SaaS subscription - Growth plan (Oct 2026)", "998314", "1", "1,00,000.00", "1,00,000.00", "9,000.00", "9,000.00"},
                        {"2", "Implementation & onboarding services", "998313", "1", "20,000.00", "20,000.00", "1,800.00", "1,800.00"}});
        d.gap();
        d.kv("Total Taxable Value:", "Rs. 1,20,000.00"); d.kv("CGST @ 9%:", "Rs. 10,800.00"); d.kv("SGST @ 9%:", "Rs. 10,800.00"); d.kv("Invoice Total:", "Rs. 1,41,600.00");
        d.left("Amount in words: Rupees One Lakh Forty One Thousand Six Hundred Only"); d.gap();
        d.left("Bank: HDFC Bank, A/c 50200012345678, IFSC HDFC0000123"); d.left("For " + OUR_NAME + " - Authorised Signatory");
        d.save(out);
    }

    static void exportLut(Path out) throws Exception {
        Doc d = new Doc();
        d.title("EXPORT INVOICE");
        d.left("SUPPLY MEANT FOR EXPORT UNDER LUT WITHOUT PAYMENT OF INTEGRATED TAX", true);
        d.left("LUT ARN: AD290326000123X dated 28-Mar-2026"); d.gap();
        d.left(OUR_NAME, true); d.left("No. 42, 3rd Floor, Indiranagar 100 Ft Road, Bengaluru, Karnataka 560038"); d.left("GSTIN: " + OUR_GSTIN); d.gap();
        d.kv("Invoice No:", "ACW/25-26/042"); d.kv("Invoice Date:", "12-Oct-2026"); d.kv("Place of Supply:", "96-Other Country (Outside India)"); d.kv("Currency:", "USD"); d.gap();
        d.left("Bill To:", true); d.left("Northwind Labs LLC"); d.left("500 Market Street, San Francisco, CA 94105, United States"); d.gap();
        d.table(new String[]{"#", "Description", "SAC", "Qty", "Amount (USD)"},
                new String[][]{{"1", "Software development services - October 2026 sprint", "998314", "1", "4,000.00"}});
        d.gap();
        d.kv("Total (USD):", "4,000.00"); d.kv("IGST:", "0.00 (Export under LUT)"); d.kv("INR equivalent @ 84.00:", "Rs. 3,36,000.00");
        d.gap(); d.left("Payment: SWIFT to HDFC Bank, Bengaluru. Export of services - payment to be received in convertible foreign exchange.");
        d.save(out);
    }

    static void purchaseDomestic(Path out) throws Exception {
        Doc d = new Doc();
        d.title("TAX INVOICE");
        d.left("Nimbus Hosting Solutions Private Limited", true); d.left("Unit 7, Andheri East, Mumbai, Maharashtra 400069");
        d.left("GSTIN: " + VENDOR_GSTIN + "   State Code: 27"); d.gap();
        d.kv("Invoice No:", "NHS/2026/1187"); d.kv("Date:", "08/10/2026"); d.kv("Place of Supply:", "Karnataka (29)"); d.kv("Tax payable on reverse charge:", "No"); d.gap();
        d.left("Billed To:", true); d.left(OUR_NAME); d.left("Bengaluru, Karnataka 560038"); d.left("GSTIN: " + OUR_GSTIN); d.gap();
        d.table(new String[]{"#", "Description", "SAC", "Taxable Value", "IGST Rate", "IGST Amt"},
                new String[][]{{"1", "Dedicated cloud server hosting - Oct 2026", "998315", "50,000.00", "18%", "9,000.00"}});
        d.gap();
        d.kv("Taxable Value:", "50,000.00"); d.kv("IGST @ 18%:", "9,000.00"); d.kv("Grand Total (INR):", "59,000.00");
        d.left("Rupees Fifty Nine Thousand Only"); d.save(out);
    }

    /** A CA-filed GSTR-3B for Oct 2026 that deliberately omits the RCM liability and its ITC (everything else agrees with the 4 fixture invoices). */
    static void filed3b(Path out) throws Exception {
        Doc d = new Doc();
        d.title("FORM GSTR-3B");
        d.kv("GSTIN:", OUR_GSTIN); d.kv("Legal name:", OUR_NAME); d.kv("Year / Period:", "2026-27 / October 2026"); d.kv("ARN:", "AA2910260012345"); d.gap();
        d.left("3.1 Details of outward supplies and inward supplies liable to reverse charge (Amount in Rs.)", true);
        d.table(new String[]{"Nature of supplies", "Taxable value", "Integrated tax", "Central tax", "State/UT tax", "Cess"},
                new String[][]{{"(a) Outward taxable supplies", "120000.00", "0.00", "10800.00", "10800.00", "0.00"},
                        {"(b) Outward taxable supplies (zero rated)", "336000.00", "0.00", "0.00", "0.00", "0.00"},
                        {"(c) Other outward supplies (nil, exempted)", "0.00", "0.00", "0.00", "0.00", "0.00"},
                        {"(d) Inward supplies (liable to reverse charge)", "0.00", "0.00", "0.00", "0.00", "0.00"},
                        {"(e) Non-GST outward supplies", "0.00", "0.00", "0.00", "0.00", "0.00"}});
        d.gap(); d.left("4. Eligible ITC (Amount in Rs.)", true);
        d.table(new String[]{"Details", "Integrated tax", "Central tax", "State/UT tax", "Cess"},
                new String[][]{{"(A)(1) Import of goods", "0.00", "0.00", "0.00", "0.00"},
                        {"(A)(2) Import of services", "0.00", "0.00", "0.00", "0.00"},
                        {"(A)(3) Inward supplies liable to reverse charge", "0.00", "0.00", "0.00", "0.00"},
                        {"(A)(5) All other ITC", "9000.00", "0.00", "0.00", "0.00"},
                        {"(B)(1) ITC reversed - Rules 38, 42, 43 and 17(5)", "0.00", "0.00", "0.00", "0.00"},
                        {"(C) Net ITC available (A-B)", "9000.00", "0.00", "0.00", "0.00"}});
        d.save(out);
    }

    static void foreignVendor(Path out) throws Exception {
        Doc d = new Doc();
        d.title("INVOICE");
        d.left("Atlas Analytics, Inc.", true); d.left("1200 Congress Ave, Austin, TX 78701, USA"); d.left("EIN 84-1234567"); d.gap();
        d.kv("Invoice #:", "INV-2026-10-0091"); d.kv("Invoice date:", "October 1, 2026"); d.kv("Due date:", "October 31, 2026"); d.kv("Currency:", "USD"); d.gap();
        d.left("Bill to:", true); d.left(OUR_NAME); d.left("Bengaluru, Karnataka, India"); d.gap();
        d.table(new String[]{"Item", "Description", "Qty", "Unit price", "Amount"},
                new String[][]{{"1", "Atlas Analytics Business plan - annual seats (10 users), Oct 2026", "10", "120.00", "1,200.00"}});
        d.gap(); d.kv("Subtotal:", "USD 1,200.00"); d.kv("Tax:", "USD 0.00"); d.kv("Total due:", "USD 1,200.00");
        d.left("Paid by card ending 4242. Thank you for your business."); d.save(out);
    }

    // ---------- tiny layout helper ----------
    static class Doc {
        final PDDocument pd = new PDDocument();
        final PDPageContentStream cs;
        final PDType1Font reg = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        float y = 800;
        Doc() throws Exception { PDPage p = new PDPage(PDRectangle.A4); pd.addPage(p); cs = new PDPageContentStream(pd, p); }
        void text(float x, String s, boolean b, float size) throws Exception { cs.beginText(); cs.setFont(b ? bold : reg, size); cs.newLineAtOffset(x, y); cs.showText(s); cs.endText(); }
        void title(String t) throws Exception { text(240, t, true, 16); y -= 28; }
        void left(String s) throws Exception { left(s, false); }
        void left(String s, boolean b) throws Exception { text(40, s, b, 9.5f); y -= 14; }
        void kv(String k, String v) throws Exception { text(40, k, true, 9.5f); text(200, v, false, 9.5f); y -= 14; }
        void gap() { y -= 8; }
        void table(String[] head, String[][] rows) throws Exception {
            float[] xs = new float[head.length]; float w = 515f / head.length;
            List<Float> widths = new ArrayList<>();
            for (int i = 0; i < head.length; i++) widths.add(i == 1 ? 175f : (515f - 175f) / (head.length - 1));
            float x = 40; for (int i = 0; i < head.length; i++) { xs[i] = x; x += widths.get(i); }
            cs.moveTo(40, y + 11); cs.lineTo(555, y + 11); cs.stroke();
            for (int i = 0; i < head.length; i++) text(xs[i], head[i], true, 8.5f);
            y -= 13; cs.moveTo(40, y + 9); cs.lineTo(555, y + 9); cs.stroke();
            for (String[] r : rows) {
                for (int i = 0; i < r.length; i++) text(xs[i], r[i].length() > 42 && i == 1 ? r[i].substring(0, 42) : r[i], false, 8.5f);
                if (r[1].length() > 42) { y -= 11; text(xs[1], r[1].substring(42), false, 8.5f); }
                y -= 13;
            }
            cs.moveTo(40, y + 9); cs.lineTo(555, y + 9); cs.stroke();
        }
        void save(Path p) throws Exception { cs.close(); pd.save(p.toFile()); pd.close(); }
    }
}
