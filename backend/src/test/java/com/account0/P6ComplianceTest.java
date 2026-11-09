package com.account0;

import com.account0.compliance.ComplianceCalendar;
import com.account0.fixtures.FixtureGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P6ComplianceTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;

    static Optional<ComplianceCalendar.Item> find(List<ComplianceCalendar.Item> l, String code, String period) { return l.stream().filter(i -> i.code().equals(code) && i.period().equals(period)).findFirst(); }

    @Test void calendarDatesHandChecked() {
        var l = ComplianceCalendar.generate("2026-27", new ComplianceCalendar.Options(null, false));
        assertThat(l).hasSize(85);
        assertThat(find(l, "GSTR1", "2026-10").get().due()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(find(l, "GSTR3B", "2026-10").get().due()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(find(l, "GSTR3B", "2027-02").get().due()).isEqualTo(LocalDate.of(2027, 3, 20));
        assertThat(find(l, "TDS_DEPOSIT", "2026-03").get().due()).isEqualTo(LocalDate.of(2026, 4, 30));   // March special case
        assertThat(find(l, "TDS_DEPOSIT", "2026-09").get().due()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(find(l, "PF", "2026-12").get().due()).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(find(l, "TDS_26Q", "2026-27-Q2").get().due()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(find(l, "TDS_26Q", "2026-27-Q3").get().due()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(find(l, "TDS_26Q", "2025-26-Q4").get().due()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(find(l, "FORM16A", "2026-27-Q1").get().due()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(find(l, "ITR6", "FY2025-26").get().due()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(find(l, "AOC4", "FY2025-26").get().due()).isEqualTo(LocalDate.of(2026, 10, 30));       // default AGM 30 Sep + 30
        assertThat(find(l, "MGT7A", "FY2025-26").get().due()).isEqualTo(LocalDate.of(2026, 11, 29));
        assertThat(find(l, "DIR3KYC", "FY2025-26").get().due()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(l.stream().filter(i -> i.code().equals("ADVANCE_TAX")).map(ComplianceCalendar.Item::due).toList())
                .containsExactlyInAnyOrder(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 12, 15), LocalDate.of(2027, 3, 15));
        var agm = ComplianceCalendar.generate("2026-27", new ComplianceCalendar.Options(LocalDate.of(2026, 9, 15), true));
        assertThat(find(agm, "AOC4", "FY2025-26").get().due()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(find(agm, "TAX_AUDIT", "FY2025-26")).isPresent();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ComplianceCalendar.generate("2026-28", new ComplianceCalendar.Options(null, false)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void calendarStatusAndClose() {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class);
        Map<String, Object> cal = rest.getForObject("/api/compliance/calendar?fy=2026-27&asOf=2026-11-10", Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) cal.get("items");
        Map<String, Object> g1 = items.stream().filter(i -> i.get("code").equals("GSTR1") && i.get("period").equals("2026-10")).findFirst().get();
        assertThat(g1.get("state")).isEqualTo("DUE_SOON"); assertThat(g1.get("daysLeft")).isEqualTo(1);
        Map<String, Object> g3 = items.stream().filter(i -> i.get("code").equals("GSTR3B") && i.get("period").equals("2026-10")).findFirst().get();
        assertThat(g3.get("state")).isEqualTo("UPCOMING");
        Map<String, Object> g3sep = items.stream().filter(i -> i.get("code").equals("GSTR3B") && i.get("period").equals("2026-09")).findFirst().get();
        assertThat(g3sep.get("state")).isEqualTo("OVERDUE");
        Map<String, Object> pf = items.stream().filter(i -> i.get("code").equals("PF") && i.get("period").equals("2026-10")).findFirst().get();
        assertThat(pf.get("state")).isEqualTo("NA");            // no employees recorded
        // filing history marks the GSTR-3B for Sep as done automatically
        rest.postForObject("/api/filings", TestSupport.map("return_type", "GSTR3B", "period", "2026-09", "arn", "AA2909260001", "filed_on", "2026-10-19", "filed_by", "CA"), Map.class);
        cal = rest.getForObject("/api/compliance/calendar?fy=2026-27&asOf=2026-11-10", Map.class);
        g3sep = ((List<Map<String, Object>>) cal.get("items")).stream().filter(i -> i.get("code").equals("GSTR3B") && i.get("period").equals("2026-09")).findFirst().get();
        assertThat(g3sep.get("state")).isEqualTo("DONE"); assertThat(g3sep.get("source")).isEqualTo("FILING_HISTORY"); assertThat(g3sep.get("filingRef")).isEqualTo("AA2909260001");
        // manual mark requires a reason for NA
        assertThat(rest.postForEntity("/api/compliance/mark", Map.of("code", "DPT3", "period", "FY2025-26", "status", "NA"), Map.class).getStatusCode().value()).isEqualTo(400);
        rest.postForObject("/api/compliance/mark", Map.of("code", "ITR6", "period", "FY2025-26", "status", "DONE", "done_on", "2026-10-20", "ref", "ACK123"), Map.class);
        // bulk catch-up
        Map<String, Object> cu = rest.postForObject("/api/compliance/catch-up", Map.of("fy", "2026-27", "before", "2026-11-08"), Map.class);
        assertThat(((Number) cu.get("marked")).intValue()).isGreaterThan(20);
        cal = rest.getForObject("/api/compliance/calendar?fy=2026-27&asOf=2026-11-10", Map.class);
        assertThat(((List<Map<String, Object>>) cal.get("items")).stream().filter(i -> "OVERDUE".equals(i.get("state")) && !Boolean.TRUE.equals(i.get("optional"))).toList()).isEmpty();   // only "if applicable" items remain
        // alerts: the Oct GSTR-1 is due tomorrow
        List<Map<String, Object>> alerts = rest.getForObject("/api/alerts", List.class);
        // (alerts use the real clock, so only assert the endpoint stays healthy)
        assertThat(alerts).isNotNull();

        // month close: empty month -> mostly TODO, readyToClose false; payroll/ca-pack ticks
        Map<String, Object> cl = rest.getForObject("/api/close/2026-10", Map.class);
        assertThat(cl.get("readyToClose")).isEqualTo(false);
        List<Map<String, Object>> ci = (List<Map<String, Object>>) cl.get("items");
        assertThat(ci).hasSize(12);
        assertThat(ci.stream().filter(i -> i.get("key").equals("INVOICES_CONFIRMED")).findFirst().get().get("state")).isEqualTo("TODO");
        assertThat(ci.stream().filter(i -> i.get("key").equals("TDS_DECIDED")).findFirst().get().get("state")).isEqualTo("NA");
        assertThat(rest.postForEntity("/api/close/2026-10/tick", Map.of("key", "GST_CLEAN", "state", "DONE"), Map.class).getStatusCode().value()).isEqualTo(400);   // derived items cannot be ticked
        cl = rest.postForObject("/api/close/2026-10/tick", Map.of("key", "CA_PACK_SENT", "state", "DONE"), Map.class);
        assertThat(((List<Map<String, Object>>) cl.get("items")).stream().filter(i -> i.get("key").equals("CA_PACK_SENT")).findFirst().get().get("state")).isEqualTo("DONE");
        assertThat(rest.getForEntity("/api/close/2026-13", Map.class).getStatusCode().value()).isEqualTo(400);

        // seed one confirmed sale, confirm it -> INVOICES_CONFIRMED turns DONE, NO_DUPLICATES DONE
        Map<String, Object> inv = rest.postForObject("/api/invoices", TestSupport.map("direction", "SALE", "invoice_no", "S-1", "invoice_date", "2026-10-05", "counterparty_name", "Bluepeak", "counterparty_gstin", FixtureGenerator.BUYER_GSTIN,
                "place_of_supply", "29", "supply_type", "B2B", "reverse_charge", 0, "taxable_paise", 1000000, "cgst_paise", 90000, "sgst_paise", 90000, "total_paise", 1180000, "category", "Income",
                "lines", List.of(Map.of("description", "x", "hsn_sac", "998314", "gst_rate", "18", "taxable_paise", 1000000, "cgst_paise", 90000, "sgst_paise", 90000))), Map.class);
        rest.postForObject("/api/invoices/" + inv.get("id") + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        cl = rest.getForObject("/api/close/2026-10", Map.class);
        ci = (List<Map<String, Object>>) cl.get("items");
        assertThat(ci.stream().filter(i -> i.get("key").equals("INVOICES_CONFIRMED")).findFirst().get().get("state")).isEqualTo("DONE");
        assertThat(ci.stream().filter(i -> i.get("key").equals("GST_CLEAN")).findFirst().get().get("state")).isEqualTo("DONE");
        for (Map<String, Object> i : ci) assertThat(String.valueOf(i.get("why"))).isNotBlank();
    }
}
