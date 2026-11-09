package com.account0;

import com.account0.fixtures.FixtureGenerator;
import com.account0.learn.Corpus;
import com.account0.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupport.FakeLlmConfig.class)
class P7LearnTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    @Autowired TestSupport.FakeTransport fake;
    @Autowired Corpus corpus;

    List<String> top(String q) {
        System.out.println("TOP[" + q + "] = " + corpus.search(q, 6).stream().map(h -> h.entry().id() + ":" + Math.round(h.score())).toList()); return corpus.search(q, 4).stream().map(h -> h.entry().id()).toList(); }

    @Test void retrievalFindsTheRightSourcesAndDeclinesOffTopic() {
        assertThat(corpus.all().size()).isGreaterThan(50);
        assertThat(top("What is reverse charge on a foreign vendor's software subscription?")).containsAnyOf("igst-5-3", "term-rcm", "cgst-9-3");
        assertThat(top("When can I claim input tax credit?")).contains("cgst-16");
        assertThat(top("what is LUT")).contains("term-lut");
        assertThat(top("TDS on consultant fees")).contains("it-194j");
        assertThat(top("when is GSTR-3B due")).contains("cgst-39");
        assertThat(top("What is the penalty for late filing?")).containsAnyOf("cgst-47", "cgst-50");
        assertThat(top("recipe for biryani")).isEmpty();
        assertThat(top("what is a good biryani recipe")).isEmpty();
        assertThat(top("who won the cricket match yesterday")).isEmpty();
        // every WhyNotes citation exists
        Map<String, Object> why = rest.getForObject("/api/learn/why", Map.class);
        assertThat(why).containsKeys("3.1d", "4A2", "6.1", "RCM", "LUT");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void askIsGroundedAndValidated() {
        int before = fake.requests.size();
        Map<String, Object> off = rest.postForObject("/api/learn/ask", Map.of("question", "recipe for biryani"), Map.class);
        assertThat(off.get("answered")).isEqualTo(false);
        assertThat(fake.requests.size()).isEqualTo(before);                    // declined WITHOUT spending an LLM call

        fake.responder = r -> new LlmResponse("Under reverse charge you pay the GST yourself on imported services [igst-5-3] and claim it back as credit [cgst-16]. Also see [made-up-9].", 500, 80, "end_turn");
        Map<String, Object> a = rest.postForObject("/api/learn/ask", Map.of("question", "Reverse charge on imported services - when is input tax credit allowed?"), Map.class);
        assertThat(a.get("answered")).isEqualTo(true); assertThat(a.get("grounded")).isEqualTo(true);
        List<Map<String, Object>> cit = (List<Map<String, Object>>) a.get("citations");
        assertThat(cit.stream().map(c -> c.get("id")).toList()).containsExactlyInAnyOrder("igst-5-3", "cgst-16");
        assertThat(String.valueOf(a.get("answer"))).doesNotContain("made-up-9");
        assertThat((Collection<String>) a.get("removedCitations")).contains("made-up-9");
        assertThat(cit.get(0).get("citation")).isNotNull();
        assertThat(fake.requests.get(fake.requests.size() - 1).content().get(0).get("text").toString()).contains("SOURCES").contains("[igst-5-3]");

        fake.responder = r -> new LlmResponse("Sure, it is simple.", 100, 10, "end_turn");
        Map<String, Object> ug = rest.postForObject("/api/learn/ask", Map.of("question", "when can I claim input tax credit?"), Map.class);
        assertThat(ug.get("grounded")).isEqualTo(false); assertThat(ug.get("warning")).isNotNull();
        assertThat(rest.getForObject("/api/learn/history", List.class)).hasSizeGreaterThanOrEqualTo(3);
        assertThat(rest.postForEntity("/api/learn/ask", Map.of("question", ""), Map.class).getStatusCode().value()).isEqualTo(400);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void walkthroughsUseYourNumbersAndDiy() {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class);
        seed(TestSupport.map("direction", "PURCHASE", "invoice_no", "INV-2026-10-0091", "invoice_date", "2026-10-01", "counterparty_name", "Atlas Analytics", "counterparty_country", "USA", "place_of_supply", "29",
                "supply_type", "IMPORT_SERVICE_RCM", "reverse_charge", 1, "itc_eligible", 1, "taxable_paise", 10080000, "total_paise", 10080000, "category", "Expense",
                "lines", List.of(Map.of("description", "Seats", "hsn_sac", "998316", "gst_rate", "18", "taxable_paise", 10080000))), true);
        Map<String, Object> w = rest.getForObject("/api/learn/walkthrough/rcm?period=2026-10", Map.class);
        assertThat(w.get("empty")).isEqualTo(false);
        String s = String.valueOf(w.get("steps"));
        assertThat(s).contains("INV-2026-10-0091").contains("1814400").contains("igst-5-3");
        Map<String, Object> g = rest.getForObject("/api/learn/walkthrough/gstr3b?period=2026-10", Map.class);
        assertThat(String.valueOf(g.get("steps"))).contains("3.1(d)").contains("Atlas Analytics").contains("2026-11-20").contains("1814400");
        for (Map<String, Object> st : (List<Map<String, Object>>) g.get("steps")) assertThat(st.get("title")).isNotNull();
        assertThat(rest.getForObject("/api/learn/walkthrough/itc?period=2026-10", Map.class).get("title")).isEqualTo("Your input tax credit in October 2026");
        assertThat(rest.getForObject("/api/learn/walkthrough/tds?period=2026-10", Map.class).get("topic")).isEqualTo("tds");
        assertThat(rest.getForEntity("/api/learn/walkthrough/nope?period=2026-10", Map.class).getStatusCode().value()).isEqualTo(400);
        // ask with a period pulls YOUR figures into the prompt (computed by code)
        fake.responder = r -> new LlmResponse("Your RCM IGST is shown in the facts [igst-5-3].", 100, 20, "end_turn");
        Map<String, Object> a = rest.postForObject("/api/learn/ask", Map.of("question", "Explain my reverse charge GSTR-3B for October 2026"), Map.class);
        assertThat(String.valueOf(a.get("usedFacts"))).contains("3.1(d)").contains("18,144.00");

        // DIY coaching
        Map<String, Object> d = rest.getForObject("/api/learn/diy/2026-10", Map.class);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) d.get("steps");
        assertThat(steps).hasSize(8);
        assertThat(String.valueOf(steps.get(1).get("text"))).contains("Prepare Offline");
        assertThat(String.valueOf(steps.get(1).get("title"))).contains("2026-11-11");
        Map<String, Object> d2 = rest.postForObject("/api/learn/diy/2026-10/GSTR1", Map.of("done", true), Map.class);
        assertThat(((Map) d2.get("progress")).get("done")).isEqualTo(1);
        assertThat(rest.postForEntity("/api/learn/diy/2026-10/BOGUS", Map.of(), Map.class).getStatusCode().value()).isEqualTo(400);
        Map<String, Object> ready = rest.getForObject("/api/learn/diy-readiness", Map.class);
        assertThat(ready.get("comparedMonths")).isEqualTo(0); assertThat(String.valueOf(ready.get("verdict"))).contains("shadow mode");
        // narrate = exactly one LLM call with the deterministic steps
        int n = fake.requests.size(); fake.responder = r -> new LlmResponse("Friendly text", 10, 10, "end_turn");
        assertThat(rest.postForObject("/api/learn/walkthrough/rcm/narrate?period=2026-10", null, Map.class).get("narration")).isEqualTo("Friendly text");
        assertThat(fake.requests.size()).isEqualTo(n + 1);
    }
}
