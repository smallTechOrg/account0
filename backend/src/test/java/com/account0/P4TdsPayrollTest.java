package com.account0;

import com.account0.fixtures.FixtureGenerator;
import com.account0.payroll.Salary192;
import com.account0.tds.*;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P4TdsPayrollTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    static final long R = 100;

    static List<TdsEngine.Payment> pays(long... rupees) { List<TdsEngine.Payment> l = new ArrayList<>(); for (int i = 0; i < rupees.length; i++) l.add(new TdsEngine.Payment(i + 1, rupees[i] * R)); return l; }

    @Test void tds194cThresholdsAndCatchUp() {
        var s = TdsSections.ALL.get("194C");
        var r = TdsEngine.compute(s, TdsSections.PayeeType.COMPANY_OR_FIRM, true, pays(20_000, 40_000, 50_000, 10_000));
        assertThat(r.get(0).tdsPaise()).isEqualTo(0);                 // below both thresholds
        assertThat(r.get(0).status()).isEqualTo("BELOW_THRESHOLD");
        assertThat(r.get(1).tdsPaise()).isEqualTo(800 * R);           // single > 30,000 -> 2% of 40,000
        assertThat(r.get(2).tdsPaise()).isEqualTo(1_400 * R);         // cum 1,10,000 > 1L: 2% of 1,10,000 = 2,200 less 800 already
        assertThat(r.get(3).tdsPaise()).isEqualTo(200 * R);
        assertThat(r.stream().mapToLong(TdsEngine.Result::tdsPaise).sum()).isEqualTo(2_400 * R);
        var ind = TdsEngine.compute(s, TdsSections.PayeeType.INDIVIDUAL_HUF, true, pays(40_000));
        assertThat(ind.get(0).tdsPaise()).isEqualTo(400 * R);         // 1% for individuals
    }
    @Test void tds194jAnd194qAndNoPan() {
        var j = TdsEngine.compute(TdsSections.ALL.get("194J_PROF"), TdsSections.PayeeType.COMPANY_OR_FIRM, true, pays(40_000, 20_000));
        assertThat(j.get(0).tdsPaise()).isEqualTo(0);
        assertThat(j.get(1).tdsPaise()).isEqualTo(6_000 * R);         // 10% of 60,000 once > 50,000
        var q = TdsEngine.compute(TdsSections.ALL.get("194Q"), TdsSections.PayeeType.COMPANY_OR_FIRM, true, pays(4_000_000, 2_000_000));
        assertThat(q.get(0).tdsPaise()).isEqualTo(0);
        assertThat(q.get(1).tdsPaise()).isEqualTo(1_000 * R);         // 0.1% of the 10 lakh above 50 lakh
        var np = TdsEngine.compute(TdsSections.ALL.get("194J_TECH"), TdsSections.PayeeType.COMPANY_OR_FIRM, false, pays(60_000));
        assertThat(np.get(0).tdsPaise()).isEqualTo(12_000 * R);       // 20% Sec 206AA
        assertThat(TdsEngine.tds(1_234_50, 200)).isEqualTo(2_500);      // 2% of 1234.50 = 24.69 -> ₹25
    }
    @Test void salary192NewRegime() {
        assertThat(Salary192.annualTax(1_500_000 * R).totalTax()).isEqualTo(97_500 * R);   // taxable 14,25,000: 20,000+40,000+33,750 = 93,750 + 4% cess
        assertThat(Salary192.annualTax(1_275_000 * R).totalTax()).isEqualTo(0);            // taxable 12,00,000 -> 87A rebate
        assertThat(Salary192.annualTax(1_290_000 * R).totalTax()).isEqualTo(15_600 * R);   // marginal relief: tax = 15,000 + 4% cess
        assertThat(Salary192.annualTax(400_000 * R).totalTax()).isEqualTo(0);
        assertThat(Salary192.annualTax(6_000_000 * R).needsCaReview()).isTrue();
        assertThat(Salary192.monthlyTds(0, 125_000 * R, 12, 0)).isEqualTo(8_125 * R);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void apiFlow() throws Exception {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN, "tan", "BLRA12345B")), Map.class);
        long nim = seed("NHS/1", "2026-10-08", "Nimbus Hosting", FixtureGenerator.VENDOR_GSTIN, null, 40_000);
        long nim2 = seed("NHS/2", "2026-11-08", "Nimbus Hosting", FixtureGenerator.VENDOR_GSTIN, null, 20_000);
        long atlas = seed("ATL/1", "2026-10-01", "Atlas Analytics", null, "USA", 100_000);
        List<Map<String, Object>> pending = rest.getForObject("/api/tds/pending", List.class);
        assertThat(pending).hasSize(3);
        assertThat(pending.stream().filter(p -> Boolean.TRUE.equals(p.get("nonResident"))).count()).isEqualTo(1);
        // non-resident cannot be assigned a domestic section
        ResponseEntity<Map> bad = rest.postForEntity("/api/tds/assign", Map.of("invoiceId", atlas, "section", "194J_TECH"), Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400); assertThat(String.valueOf(bad.getBody().get("error"))).contains("195");
        Map<String, Object> a1 = rest.postForObject("/api/tds/assign", Map.of("invoiceId", nim, "section", "194J_PROF", "saveRule", true), Map.class);
        assertThat(a1.get("tds_paise")).isEqualTo(0);    // 40,000 < 50,000 so far
        assertThat(a1.get("payee_pan")).isEqualTo(FixtureGenerator.VENDOR_GSTIN.substring(2, 12));
        rest.postForObject("/api/tds/assign", Map.of("invoiceId", nim2, "section", "194J_PROF"), Map.class);
        Map<String, Object> reg = rest.getForObject("/api/tds/register?fy=2026-27", Map.class);
        assertThat(reg.get("totalTdsPaise")).isEqualTo(600_000);   // 10% of 60,000
        List<Map<String, Object>> rows = (List<Map<String, Object>>) reg.get("rows");
        assertThat(rows.get(1).get("tds_paise")).isEqualTo(600_000);
        assertThat(String.valueOf(rows.get(1).get("reason"))).contains("crossed");
        // quarter filter + 26Q csv
        String csv = rest.getForObject("/api/tds/26q.csv?fy=2026-27&quarter=3", String.class);
        assertThat(csv).startsWith("TAN,BLRA12345B").contains("194J_PROF").contains("6000.0");
        assertThat(rest.getForObject("/api/tds/register?fy=2026-27&quarter=2", Map.class).get("rows")).isEqualTo(List.of());
        // rule remembered a section for the vendor
        assertThat(String.valueOf(rest.getForObject("/api/rules", List.class))).contains("194J_PROF");
        // deposit
        long eid = ((Number) rows.get(1).get("id")).longValue();
        assertThat(rest.postForObject("/api/tds/" + eid + "/deposit", Map.of("challan_no", "00123", "bsr_code", "0510308", "deposited_on", "2026-12-05"), Map.class).get("deposited_on")).isEqualTo("2026-12-05");
        List<Map<String, Object>> alerts = rest.getForObject("/api/alerts", List.class);
        assertThat(alerts.stream().map(x -> x.get("code")).toList()).contains("TDS_SEC195");

        // payroll
        String csvIn = "employee_id,name,pan,month,gross\nE1,Asha Rao,ABCPR1234D,2026-04,125000\nE1,Asha Rao,ABCPR1234D,2026-05,125000\nE2,Ravi K,,2026-04,50000\nE3,Old Guy,ABCPG1234D,2026-04,300000\n";
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csvIn.getBytes()) { public String getFilename() { return "p.csv"; } });
        HttpHeaders hh = new HttpHeaders(); hh.setContentType(MediaType.MULTIPART_FORM_DATA);
        Map<String, Object> imp = rest.postForObject("/api/payroll/import", new HttpEntity<>(form, hh), Map.class);
        assertThat(imp.get("rows")).isEqualTo(4); assertThat(imp.get("employees")).isEqualTo(3);
        Map<String, Object> pr = rest.getForObject("/api/payroll?fy=2026-27", Map.class);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) pr.get("lines");
        Map<String, Object> e1apr = lines.stream().filter(l -> l.get("employee_id").equals("E1") && l.get("month").equals("2026-04")).findFirst().get();
        assertThat(e1apr.get("tds_paise")).isEqualTo(812_500);           // 97,500 / 12
        Map<String, Object> e1may = lines.stream().filter(l -> l.get("employee_id").equals("E1") && l.get("month").equals("2026-05")).findFirst().get();
        assertThat(e1may.get("tds_paise")).isEqualTo(812_500);           // (97,500 - 8,125)/11 = 8,125
        Map<String, Object> e2 = lines.stream().filter(l -> l.get("employee_id").equals("E2")).findFirst().get();
        assertThat(e2.get("tds_paise")).isEqualTo(0);                    // 6,00,000 p.a. -> under rebate
        Map<String, Object> e3 = lines.stream().filter(l -> l.get("employee_id").equals("E3")).findFirst().get();
        assertThat(((Number) e3.get("tds_paise")).longValue()).isEqualTo(Salary192.monthlyTds(0, 300_000 * R, 12, 0));
        String q24 = rest.getForObject("/api/payroll/24q.csv?fy=2026-27&quarter=1", String.class);
        assertThat(q24).contains("E1,Asha Rao,ABCPR1234D,2026-04,125000.0,8125.0");
        // bad CSV
        form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("employee_id,month,gross\nE9,April,100\n".getBytes()) { public String getFilename() { return "p.csv"; } });
        ResponseEntity<Map> badCsv = rest.postForEntity("/api/payroll/import", new HttpEntity<>(form, hh), Map.class);
        assertThat(badCsv.getStatusCode().value()).isEqualTo(400);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private long seed(String no, String date, String name, String gstin, String country, long taxableRupees) {
        Map<String, Object> inv = TestSupport.map("direction", "PURCHASE", "invoice_no", no, "invoice_date", date, "counterparty_name", name, "place_of_supply", country == null ? "29" : "29",
                "supply_type", country == null ? "DOMESTIC" : "IMPORT_SERVICE_RCM", "reverse_charge", country == null ? 0 : 1, "itc_eligible", 1, "taxable_paise", taxableRupees * R, "total_paise", taxableRupees * R, "category", "Expense",
                "lines", List.of(Map.of("description", "svc", "hsn_sac", "998314", "gst_rate", country == null ? "0" : "18", "taxable_paise", taxableRupees * R)));
        if (gstin != null) inv.put("counterparty_gstin", gstin);
        if (country != null) inv.put("counterparty_country", country);
        Map<String, Object> r = rest.postForObject("/api/invoices", inv, Map.class);
        long id = ((Number) r.get("id")).longValue();
        if (country != null) rest.postForObject("/api/invoices/" + id + "/compute-rcm", null, Map.class);
        Map<String, Object> c = rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        assertThat(c.get("status")).as(String.valueOf(c)).isEqualTo("CONFIRMED");
        return id;
    }
}
