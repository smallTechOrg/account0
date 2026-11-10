package com.account0;

import com.account0.fixtures.FixtureGenerator;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.*;

import static com.account0.TestSupport.map;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 11 items 1-2: each purchase's ITC is claimed exactly once; user accept/reject of 2B lines. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class P11ItcClaimsTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    static long n(Map<String, Object> m, String k) { return ((Number) m.get(k)).longValue(); }
    static final String V = FixtureGenerator.VENDOR_GSTIN;

    @SuppressWarnings("unchecked")
    static Map<String, Object> fig(Map<String, Object> r, String table, String key) {
        return ((List<Map<String, Object>>) r.get(table)).stream().filter(f -> f.get("key").equals(key)).findFirst().orElseThrow();
    }
    long a5(String period) { return n(fig(rest.getForObject("/api/gst/3b?period=" + period, Map.class), "table4", "4A5"), "igst_paise"); }

    long purchase(String no, String date, long igst) {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class);
        long tx = igst * 100 / 18;
        Map<String, Object> b = map("direction", "PURCHASE", "invoice_no", no, "invoice_date", date, "counterparty_name", "Nimbus", "counterparty_gstin", V,
                "place_of_supply", "29", "supply_type", "DOMESTIC", "reverse_charge", 0, "itc_eligible", 1, "taxable_paise", tx, "igst_paise", igst, "total_paise", tx + igst, "category", "Hosting",
                "lines", List.of(Map.of("description", "h", "hsn_sac", "998315", "gst_rate", "18", "taxable_paise", tx, "igst_paise", igst)));
        long id = ((Number) rest.postForObject("/api/invoices", b, Map.class).get("id")).longValue();
        rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        return id;
    }
    /** lines: {docNo, igst rupees}. */
    Map<String, Object> upload2b(String mmyyyy, String[]... lines) {
        StringBuilder inv = new StringBuilder();
        for (String[] l : lines) inv.append(inv.length() > 0 ? "," : "").append("{\"inum\":\"").append(l[0]).append("\",\"dt\":\"10-08-2026\",\"rev\":\"N\",\"itcavl\":\"Y\",\"txval\":1000,\"igst\":").append(l[1]).append(",\"cgst\":0,\"sgst\":0,\"cess\":0}");
        String js = "{\"data\":{\"gstin\":\"" + FixtureGenerator.OUR_GSTIN + "\",\"rtnprd\":\"" + mmyyyy + "\",\"docdata\":{\"b2b\":[" + (lines.length == 0 ? "" : "{\"ctin\":\"" + V + "\",\"trdnm\":\"Nimbus\",\"inv\":[" + inv + "]}") + "]}}}";
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(js.getBytes()) { public String getFilename() { return "2b.json"; } });
        return rest.postForObject("/api/itc/2b/upload", form, Map.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> claimRow(Map<String, Object> g, long invoiceId) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) g.get("itcClaims")).get("rows")).stream().filter(r -> n(r, "invoiceId") == invoiceId).findFirst().orElseThrow();
    }

    // Item 1: provisional claim in August (no 2B), then the invoice appears in September's 2B -> claimed once (August)
    @SuppressWarnings("unchecked")
    @Test @Order(1) void provisionalThenLater2bIsClaimedOnce() {
        long x = purchase("NHS/801", "2026-08-10", 90_000);
        assertThat(a5("2026-08")).isEqualTo(90_000);
        upload2b("092026", new String[]{"NHS-801", "900"});
        Map<String, Object> sep = rest.getForObject("/api/gst/3b?period=2026-09", Map.class);
        assertThat(n(fig(sep, "table4", "4A5"), "igst_paise")).isEqualTo(0);
        assertThat(String.join(" ", (List<String>) sep.get("notes"))).contains("already claimed in 2026-08");
        assertThat(claimRow(sep, x)).containsEntry("claimedIn", "2026-08").containsEntry("claimedHere", false);
        assertThat(a5("2026-08")).isEqualTo(90_000);   // sum across periods = 900 once

        // August filed + locked -> snapshot is authoritative even after a later August 2B without the invoice
        Map<String, Object> f = rest.postForObject("/api/filings", Map.of("return_type", "GSTR3B", "period", "2026-08", "arn", "AA2908260000001", "filed_on", "2026-09-20"), Map.class);
        upload2b("082026");
        Map<String, Object> aug = rest.getForObject("/api/gst/3b?period=2026-08", Map.class);
        assertThat(n(fig(aug, "table4", "4A5"), "igst_paise")).isEqualTo(90_000);
        assertThat(aug.get("itcMode")).isEqualTo("FILED");
        assertThat(claimRow(aug, x)).containsEntry("filed", true).containsEntry("claimedHere", true);
        assertThat(a5("2026-09")).isEqualTo(0);

        // unlocking August (with a reason) makes it live again: August's 2B lacks it, September's 2B has it -> claimed in September only
        rest.postForObject("/api/filings/" + n(f, "id") + "/unlock", Map.of("reason", "revising"), Map.class);
        assertThat(a5("2026-08")).isEqualTo(0);
        assertThat(a5("2026-09")).isEqualTo(90_000);
        rest.postForObject("/api/filings/" + n(f, "id") + "/lock", Map.of(), Map.class);   // re-lock -> re-snapshot (now nothing claimed in August)
        assertThat(a5("2026-08")).isEqualTo(0);
        assertThat(a5("2026-09")).isEqualTo(90_000);
    }

    // Item 2: accept / reject POSSIBLE_MATCH and AMOUNT_MISMATCH lines (reason required, audited, survives re-import)
    @SuppressWarnings("unchecked")
    @Test @Order(2) void acceptRejectMismatchedLines() {
        purchase("NHS/1001", "2026-10-05", 180_000);           // 2B has 1700 -> AMOUNT_MISMATCH
        purchase("NHS/1002", "2026-10-06", 36_000);            // 2B has "NHS/10002" -> POSSIBLE_MATCH
        String[][] lines = {{"NHS/1001", "1700"}, {"NHS/10002", "360"}};
        upload2b("102026", lines);
        Map<String, Object> rep = rest.getForObject("/api/itc/match?period=2026-10", Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rep.get("rows");
        Map<String, Object> mm = rows.stream().filter(r -> "AMOUNT_MISMATCH".equals(r.get("status"))).findFirst().orElseThrow();
        Map<String, Object> pm = rows.stream().filter(r -> "POSSIBLE_MATCH".equals(r.get("status"))).findFirst().orElseThrow();
        assertThat(a5("2026-10")).isEqualTo(0);

        ResponseEntity<Map> noReason = rest.postForEntity("/api/itc/2b/docs/" + n(mm, "docId") + "/decision", Map.of("decision", "ACCEPTED"), Map.class);
        assertThat(noReason.getStatusCode().value()).isEqualTo(400);
        rest.postForObject("/api/itc/2b/docs/" + n(mm, "docId") + "/decision", Map.of("decision", "ACCEPTED", "reason", "vendor short-charged; claim as per 2B"), Map.class);
        assertThat(a5("2026-10")).isEqualTo(170_000);          // the 2B tax, not the books' 1,800
        rest.postForObject("/api/itc/2b/docs/" + n(pm, "docId") + "/decision", Map.of("decision", "REJECTED", "reason", "not our invoice"), Map.class);
        Map<String, Object> g = rest.getForObject("/api/gst/3b?period=2026-10", Map.class);
        assertThat(n(fig(g, "table4", "4A5"), "igst_paise")).isEqualTo(170_000);
        assertThat(String.join(" ", (List<String>) g.get("notes"))).contains("REJECTED by you (not our invoice)");
        // re-import keeps the decisions
        upload2b("102026", lines);
        assertThat(a5("2026-10")).isEqualTo(170_000);
        rest.postForObject("/api/itc/2b/docs/" + n(pm, "docId") + "/decision", Map.of("decision", "ACCEPTED", "reason", "vendor confirmed typo"), Map.class);
        // ids changed on re-import -> fetch again
        rows = (List<Map<String, Object>>) rest.getForObject("/api/itc/match?period=2026-10", Map.class).get("rows");
        pm = rows.stream().filter(r -> "POSSIBLE_MATCH".equals(r.get("status"))).findFirst().orElseThrow();
        assertThat(pm.get("decision")).isEqualTo("REJECTED");
        rest.postForObject("/api/itc/2b/docs/" + n(pm, "docId") + "/decision", Map.of("decision", "ACCEPTED", "reason", "vendor confirmed typo"), Map.class);
        assertThat(a5("2026-10")).isEqualTo(206_000);
        List<Map<String, Object>> audit = rest.getForObject("/api/audit", List.class);
        assertThat(audit.stream().filter(a -> "gstr2b_doc".equals(a.get("entity")) && "decision".equals(a.get("action"))).count()).isGreaterThanOrEqualTo(3);
        // MATCHED lines cannot be "decided"
        assertThat(rest.postForEntity("/api/itc/2b/docs/999999/decision", Map.of("decision", "ACCEPTED", "reason", "x"), Map.class).getStatusCode().value()).isEqualTo(404);
    }
}
