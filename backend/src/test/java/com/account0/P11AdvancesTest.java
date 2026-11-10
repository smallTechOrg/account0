package com.account0;

import com.account0.advance.AdvanceService;
import com.account0.fixtures.FixtureGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.util.*;

import static com.account0.TestSupport.map;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 11 item 3: advances received (GSTR-1 11A) and adjusted (11B), and their effect on GSTR-3B 3.1(a). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P11AdvancesTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    static long n(Object m, String k) { return ((Number) ((Map<?, ?>) m).get(k)).longValue(); }

    @Test void splitIsTaxInclusive() {
        assertThat(AdvanceService.split(11_800_000, "18", true, true)).containsExactly(10_000_000, 0, 900_000, 900_000);
        assertThat(AdvanceService.split(11_800_000, "18", false, true)).containsExactly(10_000_000, 1_800_000, 0, 0);
        assertThat(AdvanceService.split(5_000_000, "18", false, false)).containsExactly(5_000_000, 0, 0, 0);
        assertThat(AdvanceService.taxable("EXPORT_LUT", "SERVICES")).isFalse();
        assertThat(AdvanceService.taxable("DOMESTIC", "GOODS")).isFalse();
        assertThat(AdvanceService.taxable("DOMESTIC", "SERVICES")).isTrue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fig(Map<String, Object> r, String table, String key) {
        return ((List<Map<String, Object>>) r.get(table)).stream().filter(f -> f.get("key").equals(key)).findFirst().orElseThrow();
    }

    long sale(String no, String date, long taxable) {
        long half = taxable * 9 / 100;
        Map<String, Object> inv = map("direction", "SALE", "invoice_no", no, "invoice_date", date, "counterparty_name", "Bluepeak", "counterparty_gstin", FixtureGenerator.BUYER_GSTIN, "place_of_supply", "29",
                "supply_type", "B2B", "reverse_charge", 0, "taxable_paise", taxable, "cgst_paise", half, "sgst_paise", half, "total_paise", taxable + 2 * half, "category", "Income",
                "lines", List.of(Map.of("description", "SaaS", "hsn_sac", "998314", "gst_rate", "18", "taxable_paise", taxable, "cgst_paise", half, "sgst_paise", half)));
        long id = n(rest.postForObject("/api/invoices", inv, Map.class), "id");
        rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        return id;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void table11AndThreeB() throws Exception {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class);
        // bank receipt of 1,18,000 on 20 Sep -> domestic services advance (intra-state)
        String csv = "Date,Narration,Debit,Credit,Balance\n20/09/2026,NEFT CR BLUEPEAK ADVANCE,,118000.00,118000.00\n";
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csv.getBytes()) { public String getFilename() { return "s.csv"; } });
        HttpHeaders hh = new HttpHeaders(); hh.setContentType(MediaType.MULTIPART_FORM_DATA);
        rest.postForObject("/api/bank/import?account=HDFC", new HttpEntity<>(form, hh), Map.class);
        long txn = n(((List<Map<String, Object>>) rest.getForObject("/api/bank/txns", List.class)).get(0), "id");
        Map<String, Object> adv = rest.postForObject("/api/advances", Map.of("receipt_date", "2026-09-20", "customer_name", "Bluepeak", "place_of_supply", "29", "kind", "DOMESTIC", "gst_rate", "18", "bank_txn_id", txn), Map.class);
        assertThat(n(adv, "taxable_paise")).isEqualTo(10_000_000); assertThat(n(adv, "cgst_paise")).isEqualTo(900_000);
        assertThat(rest.getForObject("/api/bank/txns", List.class).get(0)).extracting(t -> ((Map) t).get("match_kind")).isEqualTo("ADVANCE");
        // export advance under LUT: recorded, no tax, not in Table 11
        rest.postForObject("/api/advances", Map.of("receipt_date", "2026-09-22", "customer_name", "Globex Inc", "kind", "EXPORT_LUT", "gst_rate", "0", "gross_paise", 5_000_000), Map.class);

        Map<String, Object> g1 = rest.getForObject("/api/gst/1?period=2026-09", Map.class);
        List<Map<String, Object>> at = (List<Map<String, Object>>) ((Map) g1.get("portalJson")).get("at");
        assertThat(at).hasSize(1);
        assertThat(at.get(0)).containsEntry("pos", "29").containsEntry("sply_ty", "INTRA");
        Map<String, Object> it = ((List<Map<String, Object>>) at.get(0).get("itms")).get(0);
        assertThat(it.get("ad_amt").toString()).isEqualTo("100000.0"); assertThat(it.get("camt").toString()).isEqualTo("9000.0");
        Map<String, Object> sep = rest.getForObject("/api/gst/3b?period=2026-09", Map.class);
        assertThat(n(fig(sep, "table31", "3.1a"), "taxable_paise")).isEqualTo(10_000_000);
        assertThat(n(fig(sep, "table31", "3.1a"), "cgst_paise")).isEqualTo(900_000);

        // October invoice of 1,00,000 + GST adjusted against the advance -> 11B (txpd) and 3.1(a) nets the advance's tax out
        long inv = sale("ACW/ADV/1", "2026-10-03", 10_000_000);
        rest.postForObject("/api/advances/" + n(adv, "id") + "/adjust", Map.of("invoiceId", inv), Map.class);
        Map<String, Object> g1o = rest.getForObject("/api/gst/1?period=2026-10", Map.class);
        List<Map<String, Object>> txpd = (List<Map<String, Object>>) ((Map) g1o.get("portalJson")).get("txpd");
        assertThat(txpd).hasSize(1);
        assertThat(((Map) g1o.get("portalJson")).get("at")).isNull();
        Map<String, Object> oct = rest.getForObject("/api/gst/3b?period=2026-10", Map.class);
        assertThat(n(fig(oct, "table31", "3.1a"), "taxable_paise")).isEqualTo(0);
        assertThat(n(fig(oct, "table31", "3.1a"), "cgst_paise")).isEqualTo(0);

        // advance and invoice in the same period: not reported in Table 11
        Map<String, Object> adv2 = rest.postForObject("/api/advances", Map.of("receipt_date", "2026-11-02", "customer_name", "Bluepeak", "place_of_supply", "27", "kind", "DOMESTIC", "gst_rate", "18", "gross_paise", 1_180_000), Map.class);
        assertThat(n(adv2, "igst_paise")).isEqualTo(180_000);   // inter-state
        long inv2 = sale("ACW/ADV/2", "2026-11-05", 1_000_000);
        rest.postForObject("/api/advances/" + n(adv2, "id") + "/adjust", Map.of("invoiceId", inv2), Map.class);
        assertThat(((Map) rest.getForObject("/api/gst/1?period=2026-11", Map.class).get("portalJson")).get("at")).isNull();
        assertThat(rest.getForObject("/api/ledger/trial-balance", Map.class).get("balanced")).isEqualTo(true);
        // over-adjusting is refused
        assertThat(rest.postForEntity("/api/advances/" + n(adv, "id") + "/adjust", Map.of("invoiceId", inv, "grossPaise", 100), Map.class).getStatusCode().value()).isEqualTo(400);
    }
}
