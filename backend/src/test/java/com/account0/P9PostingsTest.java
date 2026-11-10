package com.account0;

import com.account0.fixtures.FixtureGenerator;
import com.account0.ledger.LedgerService.Line;
import com.account0.ledger.PostingService;
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
class P9PostingsTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;

    static long dr(List<Line> l) { return l.stream().mapToLong(Line::debit).sum(); }
    static long cr(List<Line> l) { return l.stream().mapToLong(Line::credit).sum(); }

    @Test void pureLinesBalance() {
        var rc = PostingService.receiptLines("HDFC", "Bluepeak", 12_960_000, 1_200_000);
        assertThat(dr(rc)).isEqualTo(cr(rc)).isEqualTo(14_160_000);
        assertThat(rc.get(1)).isEqualTo(new Line(PostingService.TDS_RECEIVABLE, 1_200_000, 0));
        var rc0 = PostingService.receiptLines("HDFC", "Bluepeak", 500, 0);
        assertThat(rc0).hasSize(2);
        var pay = PostingService.paymentLines("HDFC", "Nimbus", 10_800_000);
        assertThat(dr(pay)).isEqualTo(cr(pay));
        var ded = PostingService.tdsDeductLines("Nimbus", "194J_PROF", 1_000_000);
        assertThat(ded.get(1)).isEqualTo(new Line("TDS payable: 194J_PROF", 0, 1_000_000));
        var dep = PostingService.tdsDepositLines("TDS payable: 194J_PROF", 1_000_000);
        assertThat(dr(dep)).isEqualTo(cr(dep));
        var pr = PostingService.payrollLines(20_000_000, 3_500_000);
        assertThat(dr(pr)).isEqualTo(cr(pr)).isEqualTo(20_000_000);
        assertThat(pr.get(2)).isEqualTo(new Line("Salary payable", 0, 16_500_000));
        assertThat(PostingService.payrollLines(100, 0)).hasSize(2);       // no TDS line when zero
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void endToEndPostingsBalanceAndAreIdempotent() {
        rest.exchange("/api/company", HttpMethod.PUT, new HttpEntity<>(Map.of("legal_name", FixtureGenerator.OUR_NAME, "gstin", FixtureGenerator.OUR_GSTIN)), Map.class);
        long sale = seed("SALE", "ACW/1", "2026-10-05", "Bluepeak Retail Private Limited", FixtureGenerator.BUYER_GSTIN, 14_160_000, 12_000_000, 1_080_000, 1_080_000, 0);
        long pur = seed("PURCHASE", "NHS/1", "2026-10-08", "Nimbus Hosting Solutions Private Limited", FixtureGenerator.VENDOR_GSTIN, 11_800_000, 10_000_000, 0, 0, 1_800_000);
        String csv = "Date,Narration,Debit,Credit,Balance\n12/10/2026,NEFT CR BLUEPEAK,,129600.00,129600.00\n15/10/2026,NEFT DR NIMBUS,108000.00,,21600.00\n";
        rest.postForObject("/api/bank/import?account=HDFC", multipart(csv.getBytes(), "s.csv"), Map.class);
        // TDS: 10% on 10,00,000.00 professional fee = 1,00,000.00 (assigned by the user)
        rest.postForObject("/api/tds/assign", Map.of("invoiceId", pur, "section", "194J_PROF"), Map.class);
        List<Map<String, Object>> txns = rest.getForObject("/api/bank/txns", List.class);
        long rcpt = txnId(txns, "BLUEPEAK"), paid = txnId(txns, "NIMBUS");
        // auto-match may already have matched; (re)match explicitly with customer TDS
        rest.postForObject("/api/bank/txns/" + rcpt + "/match", Map.of("invoiceId", sale, "customerTdsPaise", 1_200_000), Map.class);
        rest.postForObject("/api/bank/txns/" + paid + "/match", Map.of("invoiceId", pur), Map.class);
        // reject: receipt + TDS above invoice total
        assertThat(rest.postForEntity("/api/bank/txns/" + rcpt + "/customer-tds", Map.of("tdsPaise", 2_000_000), Map.class).getStatusCode().value()).isEqualTo(400);

        Map<String, Long> bal = balances();
        assertThat(rest.getForObject("/api/ledger/trial-balance", Map.class).get("balanced")).isEqualTo(true);
        assertThat(bal.get("Bank: HDFC")).isEqualTo(12_960_000L - 10_800_000L);                  // Dr-Cr
        assertThat(bal.get("Receivable: Bluepeak Retail Private Limited")).isEqualTo(0L);        // fully cleared net-of-TDS
        assertThat(bal.get("Payable: Nimbus Hosting Solutions Private Limited")).isEqualTo(0L);  // gross - TDS - cash paid
        assertThat(bal.get(PostingService.TDS_RECEIVABLE)).isEqualTo(1_200_000L);
        assertThat(bal.get("TDS payable: 194J_PROF")).isEqualTo(-1_000_000L);
        // deposit the TDS challan -> TDS payable cleared
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rest.getForObject("/api/tds/register?fy=2026-27", Map.class).get("rows");
        long eid = ((Number) rows.get(0).get("id")).longValue();
        rest.postForObject("/api/tds/" + eid + "/deposit", Map.of("challan_no", "0012", "bsr_code", "0510308", "deposited_on", "2026-11-05"), Map.class);
        assertThat(balances().get("TDS payable: 194J_PROF")).isEqualTo(0L);
        // payroll: expense, TDS payable, net salary payable
        String pay = "employee_id,name,pan,month,gross\nE1,Asha,ABCDE1234F,2026-10,500000\n";
        rest.postForObject("/api/payroll/import", multipart(pay.getBytes(), "p.csv"), Map.class);
        Map<String, Object> line = ((List<Map<String, Object>>) rest.getForObject("/api/payroll?fy=2026-27", Map.class).get("lines")).get(0);
        long tds = ((Number) line.get("tds_paise")).longValue();
        assertThat(tds).isGreaterThan(0);
        bal = balances();
        assertThat(bal.get(PostingService.SALARY_EXPENSE)).isEqualTo(50_000_000L);
        assertThat(bal.get(PostingService.TDS_SALARY)).isEqualTo(-tds);
        assertThat(bal.get(PostingService.SALARY_PAYABLE)).isEqualTo(-(50_000_000L - tds));
        rest.postForObject("/api/payroll/deposit", Map.of("month", "2026-10", "challan_no", "77", "deposited_on", "2026-11-06"), Map.class);
        assertThat(balances().get(PostingService.TDS_SALARY)).isEqualTo(0L);
        // idempotent: re-import payroll, rematch, recompute -> same journals, no audit spam
        int journals = ((List) rest.getForObject("/api/ledger/journal", List.class)).size();
        int audits = ((List) rest.getForObject("/api/audit?entity=journal&limit=500", List.class)).size();
        rest.postForObject("/api/payroll/import", multipart(pay.getBytes(), "p.csv"), Map.class);
        rest.postForObject("/api/bank/rematch", null, Map.class);
        assertThat(((List) rest.getForObject("/api/ledger/journal", List.class)).size()).isEqualTo(journals);
        assertThat(((List) rest.getForObject("/api/audit?entity=journal&limit=500", List.class)).size()).isEqualTo(audits);
        assertThat(audits).isGreaterThan(0);
        // unmatch removes only that journal and keeps TB balanced
        rest.postForObject("/api/bank/txns/" + paid + "/unmatch", null, Map.class);
        assertThat(((List) rest.getForObject("/api/ledger/journal", List.class)).size()).isEqualTo(journals - 1);
        assertThat(rest.getForObject("/api/ledger/trial-balance", Map.class).get("balanced")).isEqualTo(true);
        // reopening the purchase removes its TDS journals too
        rest.postForObject("/api/invoices/" + pur + "/reopen", null, Map.class);
        assertThat(rest.getForObject("/api/ledger/trial-balance", Map.class).get("balanced")).isEqualTo(true);
        assertThat(balances().get("TDS payable: 194J_PROF")).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Map<String, Long> balances() {
        Map<String, Long> m = new HashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) rest.getForObject("/api/ledger/trial-balance", Map.class).get("accounts"))
            m.put((String) a.get("account"), ((Number) a.get("debit_paise")).longValue() - ((Number) a.get("credit_paise")).longValue());
        return m;
    }
    long txnId(List<Map<String, Object>> txns, String s) { return ((Number) txns.stream().filter(t -> String.valueOf(t.get("description")).contains(s)).findFirst().get().get("id")).longValue(); }
    HttpEntity<?> multipart(byte[] data, String name) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(data) { public String getFilename() { return name; } });
        HttpHeaders hh = new HttpHeaders(); hh.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(form, hh);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    long seed(String dir, String no, String date, String party, String gstin, long total, long taxable, long cgst, long sgst, long igst) {
        Map<String, Object> inv = TestSupport.map("direction", dir, "invoice_no", no, "invoice_date", date, "counterparty_name", party, "counterparty_gstin", gstin, "place_of_supply", "29",
                "supply_type", "SALE".equals(dir) ? "B2B" : "DOMESTIC", "reverse_charge", 0, "itc_eligible", 1,
                "taxable_paise", taxable, "cgst_paise", cgst, "sgst_paise", sgst, "igst_paise", igst, "total_paise", total, "category", "x",
                "lines", List.of(Map.of("description", "x", "hsn_sac", "998314", "gst_rate", "0", "taxable_paise", taxable, "cgst_paise", cgst, "sgst_paise", sgst, "igst_paise", igst)));
        long id = ((Number) rest.postForObject("/api/invoices", inv, Map.class).get("id")).longValue();
        Map<String, Object> c = rest.postForObject("/api/invoices/" + id + "/confirm", Map.of("acknowledgeWarnings", true), Map.class);
        assertThat(c.get("status")).as(String.valueOf(c)).isEqualTo("CONFIRMED");
        return id;
    }
}
