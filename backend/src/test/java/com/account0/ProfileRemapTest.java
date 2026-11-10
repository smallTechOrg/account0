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

/** Invoices extracted before the company profile existed get their direction once the profile is saved — no new LLM call. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupport.FakeLlmConfig.class)
class ProfileRemapTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    @Autowired TestSupport.FakeTransport fake;

    @SuppressWarnings("unchecked")
    @Test void savingProfileRemapsUnknownDirections() throws Exception {
        fake.responder = r -> new LlmResponse(P1BooksTest.PURCHASE_JSON, 3000, 600, "end_turn");
        byte[] pdf = Files.readAllBytes(Path.of("../fixtures/invoices/03-purchase-nimbus-hosting.pdf"));
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", new ByteArrayResource(pdf) { public String getFilename() { return "nimbus.pdf"; } });
        HttpHeaders hh = new HttpHeaders(); hh.setContentType(MediaType.MULTIPART_FORM_DATA);
        long batch = ((Number) rest.postForObject("/api/documents", new HttpEntity<>(form, hh), Map.class).get("batchId")).longValue();
        Map<String, Object> b = null;
        for (int i = 0; i < 50; i++) { b = rest.getForObject("/api/batches/" + batch, Map.class); if (Boolean.TRUE.equals(b.get("complete"))) break; Thread.sleep(100); }
        long invId = ((Number) ((List<Map<String, Object>>) b.get("documents")).get(0).get("invoice_id")).longValue();

        Map<String, Object> before = rest.getForObject("/api/invoices/" + invId, Map.class);
        assertThat(before.get("direction")).isNull();
        assertThat(String.valueOf(before.get("flags"))).contains("DIRECTION_UNKNOWN");

        Map<String, Object> co = rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class).getBody();
        assertThat(co.get("remapped_drafts")).isEqualTo(1);

        Map<String, Object> after = rest.getForObject("/api/invoices/" + invId, Map.class);
        assertThat(after.get("direction")).isEqualTo("PURCHASE");
        assertThat(after.get("counterparty_name")).isEqualTo("Nimbus Hosting Solutions Private Limited");
        assertThat(after.get("supply_type")).isNull();                       // tax treatment still never guessed
        assertThat(String.valueOf(after.get("flags"))).doesNotContain("DIRECTION_UNKNOWN").contains("LOW_CONFIDENCE");
        assertThat(fake.requests).hasSize(1);                                // no re-extraction

        // idempotent; a draft the user already edited is never touched
        assertThat(rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME)), Map.class).getBody().get("remapped_drafts")).isEqualTo(0);
    }
}
