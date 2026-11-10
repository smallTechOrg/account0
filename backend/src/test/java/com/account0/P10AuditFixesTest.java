package com.account0;

import com.account0.fixtures.FixtureGenerator;
import com.account0.gst.Gstr1Calculator;
import com.account0.gst.Gstr3bCalculator;
import com.account0.invoice.InvoiceChecks;
import com.account0.invoice.InvoiceService;
import com.account0.payroll.Salary192;
import com.account0.tds.TdsEngine;
import com.account0.tds.TdsSections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.account0.TestSupport.map;
import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for the independent tax-correctness audit (phase 10), using the audit's example numbers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P10AuditFixesTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    static final long R = 100;
    static long n(Map<String, Object> m, String k) { return ((Number) m.get(k)).longValue(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fig(Map<String, Object> r, String table, String key) {
        return ((List<Map<String, Object>>) r.get(table)).stream().filter(f -> f.get("key").equals(key)).findFirst().orElseThrow();
    }

    // #1 Rule 88A + Circular 98/17/2019: residual IGST credit may go to SGST, so no cash is paid
    @Test void setOffMinimisesCash() {
        Gstr3bCalculator.SetOff s = Gstr3bCalculator.SetOff.apply(new long[]{0, 100, 100, 0}, new long[]{100, 100, 0, 0});
        assertThat(s.cash(0) + s.cash(1) + s.cash(2) + s.cash(3)).isEqualTo(0);
        assertThat(s.paidBy(2, 0)).isEqualTo(100);   // IGST credit -> SGST
        assertThat(s.paidBy(1, 1)).isEqualTo(100);   // CGST credit -> CGST
        // IGST credit is still exhausted first when it exceeds the shortfall
        Gstr3bCalculator.SetOff s2 = Gstr3bCalculator.SetOff.apply(new long[]{0, 100, 100, 0}, new long[]{150, 100, 0, 0});
        assertThat(s2.paidBy(2, 0)).isEqualTo(100); assertThat(s2.paidBy(1, 0)).isEqualTo(50); assertThat(s2.paidBy(1, 1)).isEqualTo(50);
        assertThat(s2.cash(1) + s2.cash(2)).isEqualTo(0);
    }

    static Map<String, Object> dom(long id, String no, String date, long igst, long itc) {
        return map("id", id, "direction", "PURCHASE", "doc_type", "INVOICE", "supply_type", "DOMESTIC", "itc_eligible", itc, "invoice_no", no, "invoice_date", date,
                "counterparty_name", "V", "taxable_paise", igst * 100 / 18, "igst_paise", igst, "total_paise", igst * 118 / 18);
    }

    // #2 Sec 16(2)(aa) + Rule 36(4): 4A(5) only from the period's 2B; provisional without 2B; Sec 16(4) time bar
    @SuppressWarnings("unchecked")
    @Test void itc4a5LimitedTo2bLines() {
        List<Map<String, Object>> books = List.of(dom(1, "A", "2026-10-03", 900_000, 1), dom(2, "B", "2026-10-04", 180_000, 1));
        Map<String, Object> prov = Gstr3bCalculator.compute(books, "29", "2026-10", null);
        assertThat(fig(prov, "table4", "4A5")).containsEntry("igst_paise", 1_080_000L).containsEntry("status", "PROVISIONAL");
        // 2B has A (this month) and an older September invoice S that first appears now; B is missing
        var claim = List.of(map("id", 1L, "invoice_no", "A", "invoice_date", "2026-10-03", "counterparty_name", "V", "doc_type", "INVOICE", "igst_paise", 900_000L, "cgst_paise", 0L, "sgst_paise", 0L, "cess_paise", 0L, "itc_eligible", 1L),
                map("id", 7L, "invoice_no", "S", "invoice_date", "2026-09-20", "counterparty_name", "V", "doc_type", "INVOICE", "igst_paise", 50_000L, "cgst_paise", 0L, "sgst_paise", 0L, "cess_paise", 0L, "itc_eligible", 1L));
        Map<String, Object> fin = Gstr3bCalculator.compute(books, "29", "2026-10", new Gstr3bCalculator.TwoB(claim, List.of(), List.of("B not in 2B")));
        assertThat(fig(fin, "table4", "4A5")).containsEntry("igst_paise", 950_000L).doesNotContainKey("status");
        assertThat((List<String>) fin.get("notes")).contains("B not in 2B");
        // Sec 16(4): an FY 2025-26 invoice cannot be claimed in the November 2026 return
        assertThat(Gstr3bCalculator.itcTimeBarred("2026-01-15", "2026-10")).isFalse();
        assertThat(Gstr3bCalculator.itcTimeBarred("2026-01-15", "2026-11")).isTrue();
        Map<String, Object> late = Gstr3bCalculator.compute(List.of(dom(3, "OLD", "2026-01-15", 18_000, 1)), "29", "2026-11", null);
        assertThat(fig(late, "table4", "4A5")).containsEntry("igst_paise", 0L);
    }

    // #3 194I: ₹50,000 per month or part of a month (from 1 Apr 2025), not ₹6 lakh a year
    @Test void rent194iMonthlyThreshold() {
        var s = TdsSections.ALL.get("194I_LB");
        var r = TdsEngine.compute(s, TdsSections.PayeeType.COMPANY_OR_FIRM, true, List.of(new TdsEngine.Payment(1, 60_000 * R, "2026-05")));
        assertThat(r.get(0).status()).isEqualTo("DEDUCT"); assertThat(r.get(0).tdsPaise()).isEqualTo(6_000 * R);
        var low = TdsEngine.compute(s, TdsSections.PayeeType.COMPANY_OR_FIRM, true, List.of(new TdsEngine.Payment(1, 50_000 * R, "2026-05"), new TdsEngine.Payment(2, 50_000 * R, "2026-06")));
        assertThat(low).allMatch(x -> x.tdsPaise() == 0);           // ₹6 lakh a year at ₹50,000/month: never above the monthly threshold
        var two = TdsEngine.compute(s, TdsSections.PayeeType.COMPANY_OR_FIRM, true, List.of(new TdsEngine.Payment(1, 30_000 * R, "2026-07"), new TdsEngine.Payment(2, 30_000 * R, "2026-07")));
        assertThat(two.get(0).tdsPaise()).isEqualTo(0); assertThat(two.get(1).tdsPaise()).isEqualTo(6_000 * R);   // month total ₹60,000
    }

    // #4 director remuneration: 10%, no threshold
    @Test void directorFeesNoThreshold() {
        var r = TdsEngine.compute(TdsSections.ALL.get("194J_DIR"), TdsSections.PayeeType.INDIVIDUAL_HUF, true, List.of(new TdsEngine.Payment(1, 10_000 * R)));
        assertThat(r.get(0).tdsPaise()).isEqualTo(1_000 * R);
        assertThat(TdsSections.ALL.get("194J_DIR").newCode()).isEqualTo("1028");
    }

    // #5 no-PAN rate per section; #20 salary without PAN
    @Test void noPanRates() {
        assertThat(TdsSections.ALL.get("194Q").noPanRateBp()).isEqualTo(500);
        assertThat(TdsSections.ALL.get("194C").noPanRateBp()).isEqualTo(2000);
        // salary ₹1,25,000/month (normal TDS ₹8,125) without PAN -> 20% of ₹1,25,000 = ₹25,000
        assertThat(Salary192.monthlyTdsNoPan(0, 125_000 * R, 12, 0)).isEqualTo(25_000 * R);
        assertThat(Salary192.monthlyTdsNoPan(0, 50_000 * R, 12, 0)).isEqualTo(0);   // no tax payable on ₹6 lakh -> no TDS
    }

    static Map<String, Object> sale(long id, String type, String docType, String no, String pos, String gstin, long tx, long ig, long cg, String hsn) {
        return map("id", id, "direction", "SALE", "doc_type", docType, "supply_type", type, "invoice_no", no, "invoice_date", "2026-10-10", "counterparty_name", "C", "counterparty_gstin", gstin,
                "place_of_supply", pos, "taxable_paise", tx, "igst_paise", ig, "cgst_paise", cg, "sgst_paise", cg, "total_paise", tx + ig + 2 * cg,
                "lines", List.of(map("hsn_sac", hsn, "gst_rate", ig + cg == 0 ? "0" : "18", "taxable_paise", tx, "igst_paise", ig, "cgst_paise", cg, "sgst_paise", cg, "description", "x")));
    }

    // #6 debit notes ADD to the HSN summary; #26 9B nets credit (−) and debit (+) notes
    @SuppressWarnings("unchecked")
    @Test void debitNoteSignInHsnAnd9b() {
        var inv = List.of(sale(1, "B2B", "INVOICE", "INV-1", "29", FixtureGenerator.BUYER_GSTIN, 100_000, 0, 9_000, "998314"),
                sale(2, "B2B", "DEBIT_NOTE", "DN-1", "29", FixtureGenerator.BUYER_GSTIN, 10_000, 0, 900, "998314"),
                sale(3, "B2B", "CREDIT_NOTE", "CN-1", "29", FixtureGenerator.BUYER_GSTIN, 30_000, 0, 2_700, "998314"));
        Map<String, Object> r = Gstr1Calculator.compute(inv, FixtureGenerator.OUR_GSTIN, "29", "2026-10");
        Map<String, Object> h = ((List<Map<String, Object>>) r.get("hsnB2b")).get(0);
        assertThat(((BigDecimal) h.get("txval")).compareTo(new BigDecimal("800.00"))).isEqualTo(0);   // 1000 + 100 − 300
        assertThat(fig(r, "tables", "9B")).containsEntry("taxable_paise", -20_000L);                     // +100 − 300
    }

    // #7 Table 8: four buckets by buyer registration and place of supply; nil vs exempt
    @SuppressWarnings("unchecked")
    @Test void table8Buckets() {
        var a = sale(1, "NIL_EXEMPT_OUT", "INVOICE", "E-1", "27", FixtureGenerator.BUYER_GSTIN, 50_000, 0, 0, "999293"); a.put("nil_type", "NIL");
        var b = sale(2, "NIL_EXEMPT_OUT", "INVOICE", "E-2", "29", null, 20_000, 0, 0, "999293"); b.put("nil_type", "EXEMPT");
        Map<String, Object> r = Gstr1Calculator.compute(List.of(a, b), FixtureGenerator.OUR_GSTIN, "29", "2026-10");
        List<Map<String, Object>> nil = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) r.get("portalJson")).get("nil")).get("inv");
        Map<String, Map<String, Object>> by = new HashMap<>(); nil.forEach(m -> by.put((String) m.get("sply_ty"), m));
        assertThat(((BigDecimal) by.get("INTRB2B").get("nil_amt")).compareTo(new BigDecimal("500.00"))).isEqualTo(0);
        assertThat(((BigDecimal) by.get("INTRAB2C").get("expt_amt")).compareTo(new BigDecimal("200.00"))).isEqualTo(0);
        assertThat(by).doesNotContainKey("INTRAB2B");
        assertThat((List<?>) r.get("hsnB2b")).hasSize(1);   // nil/exempt supplies now in the HSN summary (#18)
    }

    // #8 Table 13: natures 1/4/5, series grouping, numeric sort ("INV-9" before "INV-10"), cancelled per series
    @SuppressWarnings("unchecked")
    @Test void table13SeriesAndNumericSort() {
        var inv = List.of(sale(1, "B2B", "INVOICE", "INV-9", "29", FixtureGenerator.BUYER_GSTIN, 100_000, 0, 9_000, "998314"),
                sale(2, "B2B", "INVOICE", "INV-10", "29", FixtureGenerator.BUYER_GSTIN, 100_000, 0, 9_000, "998314"),
                sale(3, "B2B", "INVOICE", "EXP-1", "29", FixtureGenerator.BUYER_GSTIN, 100_000, 0, 9_000, "998314"),
                sale(4, "B2B", "DEBIT_NOTE", "DN-1", "29", FixtureGenerator.BUYER_GSTIN, 10_000, 0, 900, "998314"));
        Map<String, Object> r = Gstr1Calculator.computeDocs(inv, FixtureGenerator.OUR_GSTIN, "29", "2026-10", List.of(map("invoice_no", "INV-11", "doc_type", "INVOICE")));
        List<Map<String, Object>> dd = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) r.get("portalJson")).get("doc_issue")).get("doc_det");
        assertThat(dd).extracting(m -> m.get("doc_num")).containsExactly(1, 4);
        List<Map<String, Object>> docs1 = (List<Map<String, Object>>) dd.get(0).get("docs");
        assertThat(docs1).hasSize(2);
        Map<String, Object> invSeries = docs1.stream().filter(m -> m.get("from").toString().startsWith("INV")).findFirst().orElseThrow();
        assertThat(invSeries).containsEntry("from", "INV-9").containsEntry("to", "INV-11").containsEntry("totnum", 3).containsEntry("cancel", 1).containsEntry("net_issue", 2);
    }

    // #9 intra-state RCM: CGST = SGST exactly; 1 paisa difference no longer an ERROR
    @Test void cgstSgstOnePaisaTolerance() {
        Map<String, Object> inv = map("invoice_no", "X", "invoice_date", "2026-10-01", "direction", "PURCHASE", "supply_type", "DOMESTIC", "taxable_paise", 1001L, "cgst_paise", 90L, "sgst_paise", 91L, "total_paise", 1182L);
        assertThat(InvoiceChecks.structural(inv, List.of(), FixtureGenerator.OUR_GSTIN, LocalDate.of(2026, 11, 10))).noneMatch(f -> f.code().equals("CGST_NE_SGST"));
        inv.put("sgst_paise", 95L); inv.put("total_paise", 1186L);
        assertThat(InvoiceChecks.structural(inv, List.of(), FixtureGenerator.OUR_GSTIN, LocalDate.of(2026, 11, 10))).anyMatch(f -> f.code().equals("CGST_NE_SGST"));
    }

    // #11 RCM time of supply
    @Test void rcmTimeOfSupply() {
        assertThat(InvoiceService.rcmTos("2026-10-01", null, null)).isEqualTo("2026-12-01");     // day after 60 days
        assertThat(InvoiceService.rcmTos("2026-10-01", "2026-10-05", null)).isEqualTo("2026-10-05");
        assertThat(InvoiceService.rcmTos("2026-10-01", null, "2026-11-02")).isEqualTo("2026-11-02");
    }

    // #18 HSN placeholder blocks the portal JSON
    @SuppressWarnings("unchecked")
    @Test void unknownHsnIsABlocker() {
        var x = map("id", 1L, "direction", "SALE", "doc_type", "INVOICE", "supply_type", "B2B", "invoice_no", "N-1", "invoice_date", "2026-10-10", "counterparty_name", "C", "counterparty_gstin", FixtureGenerator.BUYER_GSTIN,
                "place_of_supply", "29", "taxable_paise", 100_000L, "cgst_paise", 9_000L, "sgst_paise", 9_000L, "total_paise", 118_000L);
        Map<String, Object> r = Gstr1Calculator.compute(List.of(x), FixtureGenerator.OUR_GSTIN, "29", "2026-10");
        assertThat((List<String>) r.get("blockers")).anyMatch(b -> b.contains("UNKNOWN"));
    }

    // #21 3.2 composition/UIN rows, Table 5 split, null company GSTIN is an error
    @SuppressWarnings("unchecked")
    @Test void table32KindsTable5SplitAndMissingGstin() {
        var comp = sale(1, "B2B", "INVOICE", "C-1", "27", "27AAACB1234C1Z5", 100_000, 18_000, 0, "998314"); comp.put("buyer_kind", "COMPOSITION");
        var in = map("id", 2L, "direction", "PURCHASE", "doc_type", "INVOICE", "supply_type", "NIL_EXEMPT_IN", "invoice_no", "P-1", "invoice_date", "2026-10-10", "counterparty_name", "S",
                "counterparty_gstin", "27AAACB1234C1Z5", "taxable_paise", 40_000L, "total_paise", 40_000L);
        Map<String, Object> r = Gstr3bCalculator.compute(List.of(comp, in), "29");
        assertThat(((List<Map<String, Object>>) r.get("table32")).get(0)).containsEntry("key", "3.2C-27").containsEntry("taxable_paise", 100_000L);
        assertThat(fig(r, "table5", "5-inter")).containsEntry("taxable_paise", 40_000L);
        assertThat(fig(r, "table5", "5-intra")).containsEntry("taxable_paise", 0L);
        Map<String, Object> noGstin = Gstr3bCalculator.compute(List.of(sale(3, "B2C", "INVOICE", "B-1", "29", null, 100_000, 0, 9_000, "998314")), null);
        assertThat((List<String>) noGstin.get("errors")).isNotEmpty();
        assertThat((List<?>) noGstin.get("table32")).isEmpty();
    }

    // #22 (partial): import of goods — customs IGST is owed to customs, not the foreign vendor
    @Test void importOfGoodsCreditsCustoms() {
        var l = com.account0.ledger.LedgerService.linesFor(map("supply_type", "IMPORT_GOODS", "direction", "PURCHASE", "doc_type", "INVOICE", "counterparty_name", "Shenzhen Parts", "itc_eligible", 1L,
                "taxable_paise", 100_000L, "igst_paise", 18_000L, "total_paise", 118_000L, "category", "Expense: Parts"));
        assertThat(l).anyMatch(x -> x.account().equals("Payable: Shenzhen Parts") && x.credit() == 100_000L);
        assertThat(l).anyMatch(x -> x.account().startsWith("Payable: Customs") && x.credit() == 18_000L);
        assertThat(l.stream().mapToLong(com.account0.ledger.LedgerService.Line::debit).sum()).isEqualTo(l.stream().mapToLong(com.account0.ledger.LedgerService.Line::credit).sum());
    }

    // #11 + #9 + #2 end to end through the API
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void apiRcmTosAndSplitAnd2b() {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class);
        // intra-state domestic RCM, taxable ₹100.01 at 18% -> 9% each = ₹9.0009 -> 900 paise each; old tax/2 split gave 900/901
        Map<String, Object> b = map("direction", "PURCHASE", "invoice_no", "GTA/1", "invoice_date", "2026-10-03", "counterparty_name", "Local GTA", "counterparty_gstin", FixtureGenerator.VENDOR_GSTIN,
                "place_of_supply", "29", "supply_type", "DOMESTIC_RCM", "reverse_charge", 1, "itc_eligible", 1, "taxable_paise", 10_001, "total_paise", 10_001, "category", "Freight",
                "lines", List.of(Map.of("description", "freight", "hsn_sac", "996511", "gst_rate", "18", "taxable_paise", 10_001)));
        long id = ((Number) rest.postForObject("/api/invoices", b, Map.class).get("id")).longValue();
        Map<String, Object> rc = rest.postForObject("/api/invoices/" + id + "/compute-rcm", null, Map.class);
        assertThat(rc.get("cgst_paise")).isEqualTo(rc.get("sgst_paise"));
        assertThat(((List<Map<String, Object>>) rc.get("checks")).stream().anyMatch(f -> "RCM_TOS_UNPAID".equals(f.get("code")))).isTrue();
        assertThat(rc.get("rcm_tos_date")).isEqualTo("2026-12-03");
        rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        Map<String, Object> oct = rest.getForObject("/api/gst/3b?period=2026-10", Map.class);
        assertThat(n(fig(oct, "table31", "3.1d"), "taxable_paise")).isEqualTo(0L);                // unpaid -> not October
        assertThat(String.join(" ", (List<String>) oct.get("notes"))).contains("time of supply is 2026-12-03");
        assertThat(n(fig(rest.getForObject("/api/gst/3b?period=2026-12", Map.class), "table31", "3.1d"), "taxable_paise")).isEqualTo(10_001L);
        // record the payment date -> back in October
        rest.postForObject("/api/invoices/" + id + "/reopen", null, Map.class);
        rest.exchange("/api/invoices/" + id, HttpMethod.PUT, new HttpEntity<>(Map.of("rcm_paid_on", "2026-10-04")), Map.class);
        rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        assertThat(n(fig(rest.getForObject("/api/gst/3b?period=2026-10", Map.class), "table31", "3.1d"), "taxable_paise")).isEqualTo(10_001L);

        // 2B: one purchase matched, another missing -> 4A(5) only the matched one
        for (String[] p : new String[][]{{"NHS/1", "900000"}, {"NHS/2", "180000"}}) {
            long tx = Long.parseLong(p[1]) * 100 / 18;
            Map<String, Object> pb = map("direction", "PURCHASE", "invoice_no", p[0], "invoice_date", "2026-10-08", "counterparty_name", "Nimbus", "counterparty_gstin", FixtureGenerator.VENDOR_GSTIN,
                    "place_of_supply", "29", "supply_type", "DOMESTIC", "reverse_charge", 0, "itc_eligible", 1, "taxable_paise", tx, "igst_paise", Long.parseLong(p[1]), "total_paise", tx + Long.parseLong(p[1]), "category", "Hosting",
                    "lines", List.of(Map.of("description", "h", "hsn_sac", "998315", "gst_rate", "18", "taxable_paise", tx, "igst_paise", Long.parseLong(p[1]))));
            long pid = ((Number) rest.postForObject("/api/invoices", pb, Map.class).get("id")).longValue();
            rest.postForObject("/api/invoices/" + pid + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        }
        Map<String, Object> before = rest.getForObject("/api/gst/3b?period=2026-10", Map.class);
        assertThat(n(fig(before, "table4", "4A5"), "igst_paise")).isEqualTo(1_080_000L); assertThat(fig(before, "table4", "4A5")).containsEntry("status", "PROVISIONAL");
        String twoB = "{\"data\":{\"gstin\":\"" + FixtureGenerator.OUR_GSTIN + "\",\"rtnprd\":\"102026\",\"docdata\":{\"b2b\":[{\"ctin\":\"" + FixtureGenerator.VENDOR_GSTIN + "\",\"trdnm\":\"Nimbus\",\"inv\":["
                + "{\"inum\":\"NHS-1\",\"dt\":\"08-10-2026\",\"rev\":\"N\",\"itcavl\":\"Y\",\"txval\":50000,\"igst\":9000,\"cgst\":0,\"sgst\":0,\"cess\":0}]}],"
                + "\"isd\":[{\"ctin\":\"29AAACI1111A1Z1\",\"trdnm\":\"HQ ISD\",\"doclist\":[{\"doctyp\":\"ISDI\",\"docnum\":\"ISD-1\",\"docdt\":\"05-10-2026\",\"itcelg\":\"Y\",\"igst\":500,\"cgst\":0,\"sgst\":0,\"cess\":0}]}]}}}";
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(twoB.getBytes()) { public String getFilename() { return "2b.json"; } });
        Map<String, Object> imp = rest.postForObject("/api/itc/2b/upload", form, Map.class);
        assertThat(imp.get("documents")).isEqualTo(2);
        Map<String, Object> after = rest.getForObject("/api/gst/3b?period=2026-10", Map.class);
        assertThat(n(fig(after, "table4", "4A5"), "igst_paise")).isEqualTo(900_000L); assertThat(fig(after, "table4", "4A5")).doesNotContainKey("status");
        assertThat(n(fig(after, "table4", "4A4"), "igst_paise")).isEqualTo(50_000L);
        assertThat(String.join(" ", (List<String>) after.get("notes"))).contains("NHS/2");
    }
}
