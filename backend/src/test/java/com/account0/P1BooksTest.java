package com.account0;

import com.account0.fixtures.FixtureGenerator;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupport.FakeLlmConfig.class)
class P1BooksTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    @Autowired TestSupport.FakeTransport fake;

    static final String PURCHASE_JSON = """
            {"document_kind":{"value":"TAX_INVOICE","confidence":0.99},
             "supplier":{"name":{"value":"Nimbus Hosting Solutions Private Limited","confidence":0.98},"gstin":{"value":"%s","confidence":0.97},"country":{"value":"India","confidence":0.9}},
             "recipient":{"name":{"value":"Acme Cloudworks Private Limited","confidence":0.98},"gstin":{"value":"%s","confidence":0.97}},
             "invoice_no":{"value":"NHS/2026/1187","confidence":0.99},"invoice_date":{"value":"2026-10-08","confidence":0.7},
             "currency":{"value":"INR","confidence":0.99},"place_of_supply":{"value":"Karnataka (29)","confidence":0.95},
             "reverse_charge_printed":{"value":false,"confidence":0.9},
             "lines":[{"description":"Hosting","hsn_sac":"998315","gst_rate":"18%%","taxable_value":"50,000.00","igst":"9,000.00","confidence":0.95}],
             "totals":{"taxable_value":{"value":"50000.00","confidence":0.98},"igst":{"value":"9000.00","confidence":0.98},"total":{"value":"59,000.00","confidence":0.98}},
             "category_guess":{"value":"Expense: Cloud hosting","confidence":0.8},"notes":null}
            """.formatted(FixtureGenerator.VENDOR_GSTIN, FixtureGenerator.OUR_GSTIN);

    @SuppressWarnings("unchecked")
    @Test void uploadExtractReviewConfirmPost() throws Exception {
        Map<String, Object> co = rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class).getBody();
        assertThat(co.get("state_code")).isEqualTo("29");
        assertThat(co.get("pan")).isEqualTo("AAKCA1234B");
        ResponseEntity<Map> bad = rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("gstin", "29AAKCA1234B1Z5")), Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);

        fake.responder = r -> new LlmResponse(PURCHASE_JSON, 3000, 600, "end_turn");
        byte[] pdf = Files.readAllBytes(Path.of("../fixtures/invoices/03-purchase-nimbus-hosting.pdf"));
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", new ByteArrayResource(pdf) { public String getFilename() { return "nimbus.pdf"; } });
        HttpHeaders hh = new HttpHeaders(); hh.setContentType(MediaType.MULTIPART_FORM_DATA);
        Map<String, Object> up = rest.postForObject("/api/documents", new HttpEntity<>(form, hh), Map.class);
        long batch = ((Number) up.get("batchId")).longValue();
        Map<String, Object> b = null;
        for (int i = 0; i < 50; i++) { b = rest.getForObject("/api/batches/" + batch, Map.class); if (Boolean.TRUE.equals(b.get("complete"))) break; Thread.sleep(100); }
        assertThat(b.get("complete")).isEqualTo(true);
        Map<String, Object> doc = ((List<Map<String, Object>>) b.get("documents")).get(0);
        assertThat(doc.get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(fake.requests).hasSize(1);
        assertThat(fake.requests.get(0).content().get(0).get("type")).isEqualTo("document");

        long invId = ((Number) doc.get("invoice_id")).longValue();
        Map<String, Object> inv = rest.getForObject("/api/invoices/" + invId, Map.class);
        assertThat(inv.get("direction")).isEqualTo("PURCHASE");
        assertThat(inv.get("supply_type")).isNull();       // never guessed
        assertThat(inv.get("igst_paise")).isEqualTo(900000);
        assertThat(inv.get("place_of_supply")).isEqualTo("29");
        assertThat(inv.get("canConfirm")).isEqualTo(false);
        assertThat(String.valueOf(inv.get("flags"))).contains("LOW_CONFIDENCE"); // date at 0.7

        // re-upload same file → duplicate, no second LLM call
        rest.postForObject("/api/documents", new HttpEntity<>(form, hh), Map.class);
        assertThat(fake.requests).hasSize(1);

        Map<String, Object> noAck = rest.postForObject("/api/invoices/" + invId + "/confirm", Map.of(), Map.class);
        assertThat(String.valueOf(noAck.get("error"))).contains("Cannot confirm");
        rest.put("/api/invoices/" + invId, Map.of("supply_type", "DOMESTIC", "reverse_charge", 0, "itc_eligible", 1));
        Map<String, Object> needAck = rest.postForObject("/api/invoices/" + invId + "/confirm", Map.of(), Map.class);
        assertThat(String.valueOf(needAck.get("error"))).contains("warning");
        Map<String, Object> ok = rest.postForObject("/api/invoices/" + invId + "/confirm", Map.of("acknowledgeWarnings", true, "saveRule", true), Map.class);
        assertThat(ok.get("status")).isEqualTo("CONFIRMED");

        Map<String, Object> tb = rest.getForObject("/api/ledger/trial-balance", Map.class);
        assertThat(tb.get("balanced")).isEqualTo(true);
        assertThat(tb.get("totalDebitPaise")).isEqualTo(5900000);
        List<Map<String, Object>> rules = rest.getForObject("/api/rules", List.class);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).get("supply_type")).isEqualTo("DOMESTIC");
        List<Map<String, Object>> audit = rest.getForObject("/api/audit?entity=invoice&entityId=" + invId, List.class);
        assertThat(audit).extracting(m -> m.get("field")).contains("supply_type", "status");

        // manual RCM purchase: rate confirmed by user → deterministic RCM tax
        Map<String, Object> rcm = rest.postForObject("/api/invoices", TestSupport.map("direction", "PURCHASE", "invoice_no", "INV-1", "invoice_date", "2026-10-01",
                "counterparty_name", "Atlas Analytics, Inc.", "counterparty_country", "USA", "place_of_supply", "29", "supply_type", "IMPORT_SERVICE_RCM", "reverse_charge", 1, "itc_eligible", 1,
                "taxable_paise", 10080000, "total_paise", 10080000, "category", "Expense: Software subscriptions",
                "lines", List.of(Map.of("description", "seats", "gst_rate", "18", "taxable_paise", 10080000))), Map.class);
        long rid = ((Number) rcm.get("id")).longValue();
        Map<String, Object> computed = rest.postForObject("/api/invoices/" + rid + "/compute-rcm", null, Map.class);
        assertThat(computed.get("igst_paise")).isEqualTo(1814400);
        Map<String, Object> rc = rest.postForObject("/api/invoices/" + rid + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        assertThat(rc.get("status")).isEqualTo("CONFIRMED");
        assertThat(rest.getForObject("/api/ledger/trial-balance", Map.class).get("balanced")).isEqualTo(true);
    }
}
