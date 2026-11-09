package com.account0;

import com.account0.fixtures.FixtureGenerator;
import com.account0.itc.ItcMatcher;
import com.account0.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupport.FakeLlmConfig.class)
class P3ScrutinyTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    @Autowired TestSupport.FakeTransport fake;

    @Test void matcherUnit() {
        var books = List.of(new ItcMatcher.Book(1, "29AAA", "INV-001", 90000), new ItcMatcher.Book(2, "29AAA", "B/77", 5000), new ItcMatcher.Book(3, "29BBB", "X1", 100), new ItcMatcher.Book(4, "29CCC", "ABC-100", 7000));
        var docs = List.of(new ItcMatcher.Doc(10, "29AAA", "inv001", 90050), new ItcMatcher.Doc(11, "29AAA", "B/78", 5000), new ItcMatcher.Doc(12, "29DDD", "Z", 1), new ItcMatcher.Doc(13, "29CCC", "ABC-100", 9999));
        Map<String, String> byKey = new HashMap<>();
        for (var r : ItcMatcher.match(books, docs)) byKey.put(r.docId() + "/" + r.bookId(), r.status());
        assertThat(byKey).containsEntry("10/1", "MATCHED");          // normalised number, within ₹1
        assertThat(byKey).containsEntry("11/2", "POSSIBLE_MATCH");   // typo in number, same tax
        assertThat(byKey).containsEntry("null/3", "IN_BOOKS_NOT_IN_2B");
        assertThat(byKey).containsEntry("12/null", "IN_2B_NOT_IN_BOOKS");
        assertThat(byKey).containsEntry("13/4", "AMOUNT_MISMATCH");
        assertThat(ItcMatcher.norm("  inv/0001 ")).isEqualTo("INV0001");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private long seed(Map<String, Object> inv, boolean rcm) {
        Map<String, Object> r = rest.postForObject("/api/invoices", inv, Map.class);
        long id = ((Number) r.get("id")).longValue();
        if (rcm) rest.postForObject("/api/invoices/" + id + "/compute-rcm", null, Map.class);
        Map<String, Object> c = rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        assertThat(c.get("status")).as(String.valueOf(c)).isEqualTo("CONFIRMED");
        return id;
    }
    private ResponseEntity<Map> upload(String url, String name, byte[] data) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(data) { public String getFilename() { return name; } });
        HttpHeaders hh = new HttpHeaders(); hh.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(form, hh), Map.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void scrutinyItcAndAlerts() throws Exception {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class);
        seed(TestSupport.map("direction", "SALE", "invoice_no", "S-1", "invoice_date", "2026-10-05", "counterparty_name", "Bluepeak", "counterparty_gstin", "29AABCB1234C1ZA".equals("x") ? "" : null,
                "place_of_supply", "29", "supply_type", "B2C", "reverse_charge", 0, "taxable_paise", 1000000, "cgst_paise", 90000, "sgst_paise", 90000, "total_paise", 1180000, "category", "Income",
                "lines", List.of(Map.of("description", "x", "hsn_sac", "998314", "gst_rate", "18", "taxable_paise", 1000000, "cgst_paise", 90000, "sgst_paise", 90000))), false);
        seed(TestSupport.map("direction", "PURCHASE", "invoice_no", "NHS/2026/1187", "invoice_date", "2026-10-08", "counterparty_name", "Nimbus", "counterparty_gstin", FixtureGenerator.VENDOR_GSTIN,
                "place_of_supply", "29", "supply_type", "DOMESTIC", "reverse_charge", 0, "itc_eligible", 1, "taxable_paise", 5000000, "igst_paise", 900000, "total_paise", 5900000, "category", "Expense",
                "lines", List.of(Map.of("description", "Hosting", "hsn_sac", "998315", "gst_rate", "18", "taxable_paise", 5000000, "igst_paise", 900000))), false);
        seed(TestSupport.map("direction", "PURCHASE", "invoice_no", "ATL-9", "invoice_date", "2026-10-01", "counterparty_name", "Atlas Analytics", "counterparty_country", "USA", "place_of_supply", "29",
                "supply_type", "IMPORT_SERVICE_RCM", "reverse_charge", 1, "itc_eligible", 1, "taxable_paise", 10000000, "total_paise", 10000000, "category", "Expense",
                "lines", List.of(Map.of("description", "Seats", "hsn_sac", "998316", "gst_rate", "18", "taxable_paise", 10000000))), true);

        // CA's filed 3B (portal shape): RCM omitted, ITC overclaimed (4A5 = 12,000 vs 9,000 in books)
        String ca3b = """
                {"gstin":"%s","ret_period":"102026","sup_details":{"osup_det":{"txval":10000.00,"iamt":0,"camt":900.00,"samt":900.00,"csamt":0},"osup_zero":{"txval":0,"iamt":0,"csamt":0},
                 "osup_nil_exmp":{"txval":0},"isup_rev":{"txval":0,"iamt":0,"camt":0,"samt":0,"csamt":0},"osup_nongst":{"txval":0}},
                 "itc_elg":{"itc_avl":[{"ty":"IMPG","iamt":0,"camt":0,"samt":0,"csamt":0},{"ty":"IMPS","iamt":0,"camt":0,"samt":0,"csamt":0},{"ty":"ISRC","iamt":0,"camt":0,"samt":0,"csamt":0},{"ty":"OTH","iamt":12000.00,"camt":0,"samt":0,"csamt":0}],
                 "itc_rev":[{"ty":"RUL","iamt":0,"camt":0,"samt":0,"csamt":0}],"itc_net":{"iamt":12000.00,"camt":0,"samt":0,"csamt":0}}}
                """.formatted(FixtureGenerator.OUR_GSTIN);
        Map<String, Object> rep = upload("/api/scrutiny/upload", "3b.json", ca3b.getBytes(StandardCharsets.UTF_8)).getBody();
        assertThat(rep.get("mismatches")).isNotEqualTo(0);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rep.get("rows");
        Map<String, Object> rcm = rows.stream().filter(r -> r.get("key").equals("3.1d") && r.get("head").equals("igst")).findFirst().get();
        assertThat(rcm.get("filed")).isEqualTo(0); assertThat(rcm.get("books")).isEqualTo(1800000); assertThat(rcm.get("status")).isEqualTo("DIFF");
        Map<String, Object> itcRow = rows.stream().filter(r -> r.get("key").equals("4A5") && r.get("head").equals("igst")).findFirst().get();
        assertThat(itcRow.get("filed")).isEqualTo(1200000); assertThat(itcRow.get("books")).isEqualTo(900000);
        Map<String, Object> matchRow = rows.stream().filter(r -> r.get("key").equals("3.1a") && r.get("head").equals("cgst")).findFirst().get();
        assertThat(matchRow.get("status")).isEqualTo("MATCH");
        String qs = String.valueOf(rep.get("questions"));
        assertThat(qs).contains("Sec 5(3) IGST Act").contains("Sec 16(2)(aa)");

        assertThat(((List<Map<String, Object>>) rest.getForObject("/api/alerts", List.class)).stream().map(a -> (String) a.get("code")).toList()).contains("RCM_UNDERPAID");

        // typed figures route + PDF route (fake LLM → unconfirmed until user confirms)
        Map<String, Object> man = rest.postForObject("/api/scrutiny/manual", Map.of("period", "2026-10", "figures", Map.of("3.1a", Map.of("taxable", "10000", "cgst", "900", "sgst", "900"))), Map.class);
        assertThat(((Map) man.get("filedReturn")).get("source")).isEqualTo("MANUAL");
        fake.responder = r -> new LlmResponse("{\"period\":\"2026-10\",\"figures\":{\"3.1a\":{\"taxable\":\"10000.00\",\"igst\":\"0\",\"cgst\":\"900.00\",\"sgst\":\"900.00\",\"cess\":\"0\"},\"3.1d\":{\"taxable\":\"0\",\"igst\":\"18000\",\"cgst\":\"0\",\"sgst\":\"0\",\"cess\":\"0\"}}}", 100, 50, "end_turn");
        byte[] pdf = Files.readAllBytes(Path.of("../fixtures/invoices/03-purchase-nimbus-hosting.pdf"));
        Map<String, Object> pr = upload("/api/scrutiny/upload", "filed.pdf", pdf).getBody();
        assertThat(((Map) pr.get("filedReturn")).get("source")).isEqualTo("PDF_LLM");
        assertThat(pr.get("unconfirmedNotice")).isNotNull();
        long prId = ((Number) ((Map) pr.get("filedReturn")).get("id")).longValue();
        assertThat(((Map) rest.postForObject("/api/scrutiny/" + prId + "/confirm", null, Map.class).get("filedReturn")).get("confirmed")).isEqualTo(1);
        // the reference PDF must not enter the invoice extraction queue
        List<Map<String, Object>> queued = rest.getForObject("/api/documents?status=QUEUED", List.class);
        assertThat(queued).isEmpty();

        // GSTR-2B: Nimbus matches; a stranger invoice in 2B not in books
        String twoB = """
                {"data":{"gstin":"%s","rtnprd":"102026","docdata":{"b2b":[
                 {"ctin":"%s","trdnm":"Nimbus Hosting","supfildt":"11-11-2026","supprd":"102026","inv":[{"inum":"NHS-2026-1187","dt":"08-10-2026","val":59000,"rev":"N","itcavl":"Y","txval":50000.00,"igst":9000.00,"cgst":0,"sgst":0,"cess":0}]},
                 {"ctin":"27AAACS0000A1Z5","trdnm":"Stranger Co","inv":[{"inum":"S-55","dt":"09-10-2026","val":1180,"rev":"N","itcavl":"Y","txval":1000.00,"igst":180.00,"cgst":0,"sgst":0,"cess":0}]}]}}}
                """.formatted(FixtureGenerator.OUR_GSTIN, FixtureGenerator.VENDOR_GSTIN);
        Map<String, Object> imp = upload("/api/itc/2b/upload", "2b.json", twoB.getBytes(StandardCharsets.UTF_8)).getBody();
        assertThat(imp.get("documents")).isEqualTo(2);
        Map<String, Object> m = rest.getForObject("/api/itc/match?period=2026-10", Map.class);
        String counts = String.valueOf(((Map) m.get("summary")).get("counts"));
        assertThat(counts).contains("MATCHED=1").contains("IN_2B_NOT_IN_BOOKS=1");
        // the 2B total ITC is now ₹9,000 vs 12,000 claimed → question mentions it
        Map<String, Object> rep2 = rest.getForObject("/api/scrutiny/" + ((Map) rep.get("filedReturn")).get("id"), Map.class);
        assertThat(String.valueOf(rep2.get("questions"))).contains("GSTR-2B shows");

        List<Map<String, Object>> alerts = rest.getForObject("/api/alerts", List.class);
        List<String> codes = alerts.stream().map(a -> (String) a.get("code")).toList();
        assertThat(codes).contains("IN_2B_NOT_IN_BOOKS");
        // duplicate alert
        seed(TestSupport.map("direction", "PURCHASE", "invoice_no", "nhs/2026/1187", "invoice_date", "2026-10-09", "counterparty_name", "Nimbus", "counterparty_gstin", FixtureGenerator.VENDOR_GSTIN,
                "place_of_supply", "29", "supply_type", "DOMESTIC", "reverse_charge", 0, "itc_eligible", 1, "taxable_paise", 5000000, "igst_paise", 900000, "total_paise", 5900000, "category", "Expense",
                "lines", List.of(Map.of("description", "Hosting", "hsn_sac", "998315", "gst_rate", "18", "taxable_paise", 5000000, "igst_paise", 900000))), false);
        alerts = rest.getForObject("/api/alerts", List.class);
        assertThat(alerts.stream().map(a -> (String) a.get("code")).toList()).contains("DUPLICATE_INVOICE");
        assertThat(rest.postForObject("/api/itc/2b/upload", null, Map.class).get("error")).isNotNull();
    }
}
