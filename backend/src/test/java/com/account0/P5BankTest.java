package com.account0;

import com.account0.bank.*;
import com.account0.fixtures.FixtureGenerator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P5BankTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    static final long R = 100;

    @Test void parsingFormats() {
        assertThat(BankParser.parseDate("05/10/2026")).isEqualTo("2026-10-05");
        assertThat(BankParser.parseDate("05-Oct-26")).isEqualTo("2026-10-05");
        assertThat(BankParser.parseDate("2026-10-05")).isEqualTo("2026-10-05");
        assertThat(BankParser.amount("1,23,456.78")).isEqualTo(12345678);
        assertThat(BankParser.amount("500.00 Dr")).isEqualTo(50000);
        assertThat(BankParser.amount("(250.50)")).isEqualTo(-25050);
        assertThat(BankParser.amount("")).isEqualTo(0);
        // single Amount + Dr/Cr column, preamble rows before header
        String csv = "Account Statement,,,\nAcct 1234,,,\nTxn Date,Description,Amount,Dr/Cr\n01/10/2026,Rent,\"25,000.00\",DR\n02/10/2026,Receipt,\"1,000.00\",CR\n";
        var t = BankParser.read(csv.getBytes(), "x.csv");
        var tx = BankParser.parse(t, BankParser.detect(t.headers()));
        assertThat(tx).hasSize(2);
        assertThat(tx.get(0).debit()).isEqualTo(2_500_000); assertThat(tx.get(1).credit()).isEqualTo(100_000);
        assertThatThrows("no,header,here\n1,2,3\n");
    }
    void assertThatThrows(String csv) { try { BankParser.read(csv.getBytes(), "x.csv"); throw new AssertionError("should fail"); } catch (org.springframework.web.server.ResponseStatusException e) { assertThat(e.getStatusCode().value()).isEqualTo(400); } }

    @Test void xlsxParsing() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            var sh = wb.createSheet(); var h = sh.createRow(0);
            String[] heads = {"Date", "Narration", "Debit", "Credit", "Balance"}; for (int i = 0; i < heads.length; i++) h.createCell(i).setCellValue(heads[i]);
            var r = sh.createRow(1); r.createCell(0).setCellValue("05/10/2026"); r.createCell(1).setCellValue("UPI-ACME"); r.createCell(2).setCellValue(1500.5); r.createCell(3).setCellValue(""); r.createCell(4).setCellValue(98499.5);
            wb.write(bos);
            var t = BankParser.read(bos.toByteArray(), "s.xlsx");
            var tx = BankParser.parse(t, BankParser.detect(t.headers()));
            assertThat(tx.get(0).debit()).isEqualTo(150050); assertThat(tx.get(0).balance()).isEqualTo(9849950L);
        }
    }

    @Test void matcherScoring() {
        var inv = new BankMatcher.Invoice(1, true, "ACW/25-26/041", "2026-10-05", "Bluepeak Retail Private Limited", 14_160_000);
        var other = new BankMatcher.Invoice(2, true, "ACW/25-26/050", "2026-10-06", "Zeta Corp", 14_160_000);
        var t = new BankMatcher.Txn(9, "2026-10-12", "NEFT CR-BLUEPEAK RETAIL PVT LTD-ACW/25-26/041", "", 0, 12_960_000);   // net of 12,000 TDS
        var ranked = BankMatcher.rank(t, List.of(inv, other));
        assertThat(ranked).hasSize(1); assertThat(ranked.get(0).invoiceId()).isEqualTo(1); assertThat(BankMatcher.unambiguousStrong(ranked)).isTrue();
        assertThat(ranked.get(0).note()).contains("TDS");
        // wrong direction and unrelated amounts never match
        assertThat(BankMatcher.score(new BankMatcher.Txn(1, "2026-10-12", "x", "", 14_160_000, 0), inv)).isNull();
        assertThat(BankMatcher.score(new BankMatcher.Txn(1, "2026-10-12", "NEFT BLUEPEAK", "", 0, 500_000), inv)).isNull();
        // two identical-amount invoices without a name/number hint are ambiguous -> never auto
        var a = new BankMatcher.Invoice(3, false, "A-1", "2026-10-01", "Vendor One", 5_000_000);
        var b = new BankMatcher.Invoice(4, false, "B-1", "2026-10-02", "Vendor Two", 5_000_000);
        var rk = BankMatcher.rank(new BankMatcher.Txn(5, "2026-10-10", "NEFT TRANSFER", "", 5_000_000, 0), List.of(a, b));
        assertThat(BankMatcher.unambiguousStrong(rk)).isFalse();
        // payment before the invoice date by more than 3 days is penalised
        assertThat(BankMatcher.score(new BankMatcher.Txn(6, "2026-09-01", "NEFT NIMBUS", "", 5_900_000, 0), new BankMatcher.Invoice(7, false, "N-1", "2026-10-08", "Nimbus Hosting", 5_900_000)).score()).isLessThan(50);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void importMatchReconcile() throws Exception {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class);
        seed("SALE", "ACW/25-26/041", "2026-10-05", "Bluepeak Retail Private Limited", FixtureGenerator.BUYER_GSTIN, 14_160_000, 12_000_000, "B2B", 1_080_000, 1_080_000, 0);
        long nim = seed("PURCHASE", "NHS/2026/1187", "2026-10-08", "Nimbus Hosting Solutions Private Limited", FixtureGenerator.VENDOR_GSTIN, 5_900_000, 5_000_000, "DOMESTIC", 0, 0, 900_000);
        seed("PURCHASE", "OLD/1", "2026-06-01", "Old Vendor Pvt Ltd", null, 100_000, 100_000, "UNREGISTERED", 0, 0, 0);
        byte[] csv = Files.readAllBytes(Path.of("../fixtures/bank/hdfc-style-oct-2026.csv"));
        Map<String, Object> pv = post("/api/bank/preview", csv, "s.csv", null);
        assertThat(String.valueOf(pv.get("mapping"))).contains("date=0").contains("debit=4").contains("credit=5").contains("balance=6");
        Map<String, Object> imp = post("/api/bank/import?account=HDFC", csv, "s.csv", null);
        assertThat(imp.get("new")).isEqualTo(7);
        assertThat(((Map) imp.get("matching")).get("autoMatched")).isEqualTo(2);
        assertThat(((Map) imp.get("balanceCheck")).get("ok")).isEqualTo(true);
        // re-import is idempotent
        Map<String, Object> again = post("/api/bank/import?account=HDFC", csv, "s.csv", null);
        assertThat(again.get("new")).isEqualTo(0); assertThat(again.get("duplicates")).isEqualTo(7);
        List<Map<String, Object>> txns = rest.getForObject("/api/bank/txns", List.class);
        Map<String, Object> nimTxn = txns.stream().filter(t -> String.valueOf(t.get("description")).contains("NIMBUS")).findFirst().get();
        assertThat(nimTxn.get("status")).isEqualTo("MATCHED"); assertThat(nimTxn.get("match_source")).isEqualTo("AUTO"); assertThat(((Number) nimTxn.get("matched_invoice_id")).longValue()).isEqualTo(nim);
        Map<String, Object> bp = txns.stream().filter(t -> String.valueOf(t.get("description")).contains("BLUEPEAK")).findFirst().get();
        assertThat(bp.get("status")).isEqualTo("MATCHED"); assertThat(String.valueOf(bp.get("match_note"))).contains("TDS");
        Map<String, Object> nw = txns.stream().filter(t -> String.valueOf(t.get("description")).contains("NORTHWIND")).findFirst().get();
        assertThat(nw.get("status")).isEqualTo("UNMATCHED");        // export invoice not seeded yet -> nothing to suggest
        Map<String, Object> sal = txns.stream().filter(t -> String.valueOf(t.get("description")).contains("SALARY")).findFirst().get();
        // manual: ignore with reason; match requires right direction
        long salId = ((Number) sal.get("id")).longValue();
        assertThat(rest.postForEntity("/api/bank/txns/" + salId + "/ignore", Map.of("reason", ""), Map.class).getStatusCode().value()).isEqualTo(400);
        assertThat(rest.postForObject("/api/bank/txns/" + salId + "/ignore", Map.of("reason", "salary - see payroll"), Map.class).get("status")).isEqualTo("IGNORED");
        ResponseEntity<Map> wrong = rest.postForEntity("/api/bank/txns/" + salId + "/match", Map.of("invoiceId", nim), Map.class);
        assertThat(wrong.getBody().get("status")).isEqualTo("MATCHED");   // a payment to a purchase invoice is the right direction
        rest.postForObject("/api/bank/txns/" + salId + "/unmatch", null, Map.class);
        ResponseEntity<Map> bad = rest.postForEntity("/api/bank/txns/" + ((Number) bp.get("id")).longValue() + "/match", Map.of("invoiceId", nim), Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);        // receipt vs purchase invoice
        // outstanding: Nimbus is paid, sale is paid in full only up to net -> still ₹12,000 outstanding (TDS), OLD/1 unpaid
        List<Map<String, Object>> out = (List<Map<String, Object>>) rest.getForObject("/api/bank/summary", Map.class).get("outstanding");
        assertThat(out.stream().map(o -> o.get("invoiceNo")).toList()).contains("OLD/1", "ACW/25-26/041").doesNotContain("NHS/2026/1187");
        Map<String, Object> sale = out.stream().filter(o -> o.get("invoiceNo").equals("ACW/25-26/041")).findFirst().get();
        assertThat(sale.get("outstandingPaise")).isEqualTo(1_200_000);
        // balance break detection
        String broken = "Date,Narration,Debit,Credit,Balance\n01/11/2026,A,100.00,,900.00\n02/11/2026,B,50.00,,700.00\n";
        Map<String, Object> bi = post("/api/bank/import?account=X", broken.getBytes(), "b.csv", null);
        assertThat(((Map) bi.get("balanceCheck")).get("ok")).isEqualTo(false);
        List<Map<String, Object>> alerts = rest.getForObject("/api/alerts", List.class);
        assertThat(alerts.stream().map(a -> a.get("code")).toList()).contains("BANK_UNRECONCILED", "PAYABLE_OVERDUE");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> post(String url, byte[] data, String name, Object ignored) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(data) { public String getFilename() { return name; } });
        HttpHeaders hh = new HttpHeaders(); hh.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForObject(url, new HttpEntity<>(form, hh), Map.class);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private long seed(String dir, String no, String date, String party, String gstin, long total, long taxable, String st, long cgst, long sgst, long igst) {
        Map<String, Object> inv = TestSupport.map("direction", dir, "invoice_no", no, "invoice_date", date, "counterparty_name", party, "place_of_supply", "29", "supply_type", st, "reverse_charge", 0, "itc_eligible", 1,
                "taxable_paise", taxable, "cgst_paise", cgst, "sgst_paise", sgst, "igst_paise", igst, "total_paise", total, "category", "x",
                "lines", List.of(Map.of("description", "x", "hsn_sac", "998314", "gst_rate", "0", "taxable_paise", taxable, "cgst_paise", cgst, "sgst_paise", sgst, "igst_paise", igst)));
        if (gstin != null) inv.put("counterparty_gstin", gstin);
        Map<String, Object> r = rest.postForObject("/api/invoices", inv, Map.class);
        long id = ((Number) r.get("id")).longValue();
        Map<String, Object> c = rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        assertThat(c.get("status")).as(String.valueOf(c)).isEqualTo("CONFIRMED");
        return id;
    }
}
