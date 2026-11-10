package com.account0;

import com.account0.fixtures.FixtureGenerator;
import com.account0.gst.Gstr1Calculator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.*;

import static com.account0.TestSupport.map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P9GstGapsTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    @Test void exportCreditNoteGoesToCdnurAndCancelledDocsAreCounted() {
        List<Map<String, Object>> inv = new ArrayList<>(GstCalculatorTest.month());
        inv.add(map("id", 9L, "direction", "SALE", "doc_type", "CREDIT_NOTE", "supply_type", "EXPORT_LUT", "invoice_no", "CN/1", "invoice_date", "2026-10-20", "counterparty_name", "Northwind",
                "place_of_supply", "96", "taxable_paise", 8_400_000L, "total_paise", 8_400_000L, "lines", List.of(map("hsn_sac", "998314", "gst_rate", "0", "taxable_paise", 8_400_000L))));
        Map<String, Object> r = Gstr1Calculator.compute(inv, FixtureGenerator.OUR_GSTIN, "29", "2026-10", List.of("ACW/25-26/043"));
        Map<String, Object> json = (Map<String, Object>) r.get("portalJson");
        List<Map<String, Object>> cdnur = (List<Map<String, Object>>) json.get("cdnur");
        assertThat(cdnur).hasSize(1);
        assertThat(cdnur.get(0)).containsEntry("typ", "EXPWOP").containsEntry("ntty", "C").containsEntry("nt_num", "CN/1");
        // the note must not appear as an export invoice
        List<Map<String, Object>> exp = (List<Map<String, Object>>) json.get("exp");
        assertThat(((List<?>) exp.get(0).get("inv"))).hasSize(1);
        assertThat(((List<Map<String, Object>>) r.get("tables")).stream().anyMatch(t -> "9B-UR".equals(t.get("key")) && ((Number) t.get("taxable_paise")).longValue() == -8_400_000L)).isTrue();   // credit note nets negative (audit #26)
        // export HSN is reduced by the credit note (33,600 - 8,400 = 25,200)
        Map<String, Object> hsn = ((List<Map<String, Object>>) r.get("hsnB2c")).get(0);
        assertThat(((java.math.BigDecimal) hsn.get("txval")).compareTo(new java.math.BigDecimal("252000.00"))).isEqualTo(0);
        // Table 13: 5 issued (4 sales-side docs counted incl. the note = 3 sales + 1 note) + 1 cancelled
        Map<String, Object> docs = (Map<String, Object>) r.get("documentsIssued");
        assertThat(docs).containsEntry("cancelled", 1).containsEntry("netIssued", 3).containsEntry("count", 4);
        // audit #8: invoices are doc_num 1 (series ACW/25-26/), credit notes doc_num 5 — no longer merged into one row
        List<Map<String, Object>> dd = (List<Map<String, Object>>) ((Map<String, Object>) json.get("doc_issue")).get("doc_det");
        assertThat(dd).extracting(m -> m.get("doc_num")).containsExactly(1, 5);
        List<Map<String, Object>> det = (List<Map<String, Object>>) dd.get(0).get("docs");
        assertThat(det.get(0)).containsEntry("from", "ACW/25-26/041").containsEntry("to", "ACW/25-26/043").containsEntry("totnum", 3).containsEntry("cancel", 1).containsEntry("net_issue", 2);
        assertThat((List<Map<String, Object>>) dd.get(1).get("docs")).first().satisfies(m -> assertThat(m).containsEntry("from", "CN/1").containsEntry("totnum", 1));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void cancelEndpointAndQrmpWarning() {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN, "gst_filing_frequency", "QUARTERLY")), Map.class);
        Map<String, Object> body = TestSupport.map("direction", "SALE", "invoice_no", "ACW/C/1", "invoice_date", "2026-10-05", "counterparty_name", "Bluepeak", "counterparty_gstin", FixtureGenerator.BUYER_GSTIN,
                "place_of_supply", "29", "supply_type", "B2B", "reverse_charge", 0, "taxable_paise", 1_000_000, "cgst_paise", 90_000, "sgst_paise", 90_000, "total_paise", 1_180_000, "category", "x",
                "lines", List.of(Map.of("description", "x", "hsn_sac", "998314", "gst_rate", "18", "taxable_paise", 1_000_000, "cgst_paise", 90_000, "sgst_paise", 90_000)));
        long id = ((Number) rest.postForObject("/api/invoices", body, Map.class).get("id")).longValue();
        rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        Map<String, Object> g1 = rest.getForObject("/api/gst/1?period=2026-10", Map.class);
        assertThat(String.join(" ", (List<String>) g1.get("warnings"))).contains("QRMP");
        assertThat(((Map) g1.get("documentsIssued")).get("count")).isEqualTo(1);
        assertThat(rest.postForEntity("/api/invoices/" + id + "/cancel", Map.of(), Map.class).getStatusCode().value()).isEqualTo(400);    // reason required
        Map<String, Object> c = rest.postForObject("/api/invoices/" + id + "/cancel", Map.of("reason", "issued in error"), Map.class);
        assertThat(c.get("status")).isEqualTo("CANCELLED");
        g1 = rest.getForObject("/api/gst/1?period=2026-10", Map.class);
        assertThat(((Map) g1.get("documentsIssued"))).containsEntry("cancelled", 1).containsEntry("netIssued", 0).containsEntry("count", 1);
        Map<String, Object> g3 = rest.getForObject("/api/gst/3b?period=2026-10", Map.class);
        assertThat(((Map) ((List) g3.get("table31")).get(0)).get("taxable_paise")).isEqualTo(0);       // cancelled doc is out of 3B
        assertThat(rest.getForObject("/api/ledger/trial-balance", Map.class).get("totalDebitPaise")).isEqualTo(0);
        assertThat(rest.postForEntity("/api/invoices/" + id + "/cancel", Map.of("reason", "again"), Map.class).getStatusCode().value()).isEqualTo(400);  // not confirmed any more
    }
}
