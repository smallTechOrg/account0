package com.account0;

import com.account0.fixtures.FixtureGenerator;
import com.account0.gst.Gstr1Calculator;
import com.account0.gst.Gstr3bCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.account0.TestSupport.map;
import static org.assertj.core.api.Assertions.assertThat;

/** Hand-computed expectations for the fixture month (Oct 2026). */
class GstCalculatorTest {
    static List<Map<String, Object>> month() {
        return List.of(
                map("id", 1L, "direction", "SALE", "doc_type", "INVOICE", "supply_type", "B2B", "invoice_no", "ACW/25-26/041", "invoice_date", "2026-10-05", "counterparty_gstin", FixtureGenerator.BUYER_GSTIN, "counterparty_name", "Bluepeak",
                        "place_of_supply", "29", "taxable_paise", 12000000L, "cgst_paise", 1080000L, "sgst_paise", 1080000L, "igst_paise", 0L, "cess_paise", 0L, "total_paise", 14160000L,
                        "lines", List.of(map("hsn_sac", "998314", "gst_rate", "18", "taxable_paise", 10000000L, "cgst_paise", 900000L, "sgst_paise", 900000L, "description", "SaaS"),
                                map("hsn_sac", "998313", "gst_rate", "18", "taxable_paise", 2000000L, "cgst_paise", 180000L, "sgst_paise", 180000L, "description", "Impl"))),
                map("id", 2L, "direction", "SALE", "doc_type", "INVOICE", "supply_type", "EXPORT_LUT", "invoice_no", "ACW/25-26/042", "invoice_date", "2026-10-12", "counterparty_name", "Northwind",
                        "place_of_supply", "96", "taxable_paise", 33600000L, "total_paise", 33600000L, "lines", List.of(map("hsn_sac", "998314", "gst_rate", "0", "taxable_paise", 33600000L))),
                map("id", 3L, "direction", "PURCHASE", "doc_type", "INVOICE", "supply_type", "DOMESTIC", "itc_eligible", 1L, "invoice_no", "NHS/2026/1187", "invoice_date", "2026-10-08",
                        "taxable_paise", 5000000L, "igst_paise", 900000L, "total_paise", 5900000L),
                map("id", 4L, "direction", "PURCHASE", "doc_type", "INVOICE", "supply_type", "IMPORT_SERVICE_RCM", "itc_eligible", 1L, "invoice_no", "INV-2026-10-0091", "invoice_date", "2026-10-01",
                        "taxable_paise", 10080000L, "igst_paise", 1814400L, "total_paise", 10080000L));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fig(Map<String, Object> r, String table, String key) {
        return ((List<Map<String, Object>>) r.get(table)).stream().filter(f -> f.get("key").equals(key)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    @Test void gstr3bFixtureMonth() {
        Map<String, Object> r = Gstr3bCalculator.compute(month(), "29");
        assertThat(fig(r, "table31", "3.1a")).containsEntry("taxable_paise", 12000000L).containsEntry("cgst_paise", 1080000L).containsEntry("sgst_paise", 1080000L);
        assertThat(fig(r, "table31", "3.1b")).containsEntry("taxable_paise", 33600000L).containsEntry("igst_paise", 0L);
        assertThat(fig(r, "table31", "3.1d")).containsEntry("taxable_paise", 10080000L).containsEntry("igst_paise", 1814400L);
        assertThat(fig(r, "table4", "4A2")).containsEntry("igst_paise", 1814400L);
        assertThat(fig(r, "table4", "4A5")).containsEntry("igst_paise", 900000L);
        assertThat(fig(r, "table4", "4C")).containsEntry("igst_paise", 2714400L);
        assertThat((List<?>) fig(r, "table31", "3.1a").get("sources")).hasSize(1);
        Map<String, Object> pay = (Map<String, Object>) r.get("payment61");
        List<Map<String, Object>> heads = (List<Map<String, Object>>) pay.get("heads");
        // IGST credit 27,144 pays CGST 10,800 and SGST 10,800 (Rule 88A), leaving 5,544; RCM 18,144 in cash.
        assertThat(heads.get(1)).containsEntry("paidByIgstItcPaise", 1080000L).containsEntry("cashPaise", 0L);
        assertThat(heads.get(2)).containsEntry("paidByIgstItcPaise", 1080000L).containsEntry("cashPaise", 0L);
        assertThat(pay.get("cashTotalPaise")).isEqualTo(1814400L);
        assertThat(((Map<String, Object>) pay.get("itcCarriedForwardPaise")).get("igst")).isEqualTo(554400L);
    }

    @Test void setOffNeverCrossUtilisesCgstSgst() {
        // liability: SGST 100; credit: CGST 100 → SGST must be paid in cash
        Gstr3bCalculator.SetOff s = Gstr3bCalculator.SetOff.apply(new long[]{0, 0, 100, 0}, new long[]{0, 100, 0, 0});
        assertThat(s.cash(2)).isEqualTo(100);
        // liability IGST 100; credit CGST 60 + SGST 60 → both used for IGST (after IGST credit nil)
        Gstr3bCalculator.SetOff s2 = Gstr3bCalculator.SetOff.apply(new long[]{100, 0, 0, 0}, new long[]{0, 60, 60, 0});
        assertThat(s2.paidBy(0, 1)).isEqualTo(60); assertThat(s2.paidBy(0, 2)).isEqualTo(40); assertThat(s2.cash(0)).isEqualTo(0);
    }

    @Test void ineligibleItcIsReversedIn4B() {
        List<Map<String, Object>> m = List.of(map("id", 9L, "direction", "PURCHASE", "doc_type", "INVOICE", "supply_type", "DOMESTIC", "itc_eligible", 0L, "invoice_no", "X", "invoice_date", "2026-10-02",
                "taxable_paise", 1000000L, "cgst_paise", 90000L, "sgst_paise", 90000L, "total_paise", 1180000L));
        Map<String, Object> r = Gstr3bCalculator.compute(m, "29");
        assertThat(fig(r, "table4", "4A5")).containsEntry("cgst_paise", 90000L);
        assertThat(fig(r, "table4", "4B1")).containsEntry("cgst_paise", 90000L);
        assertThat(fig(r, "table4", "4C")).containsEntry("cgst_paise", 0L);
    }

    @SuppressWarnings("unchecked")
    @Test void gstr1Shapes() {
        List<Map<String, Object>> m = new ArrayList<>(month());
        m.add(map("id", 5L, "direction", "SALE", "doc_type", "INVOICE", "supply_type", "B2C", "invoice_no", "ACW/25-26/043", "invoice_date", "2026-10-20", "place_of_supply", "27",
                "taxable_paise", 15000000L, "igst_paise", 2700000L, "total_paise", 17700000L));           // inter-state > 1 lakh → B2CL
        m.add(map("id", 6L, "direction", "SALE", "doc_type", "INVOICE", "supply_type", "B2C", "invoice_no", "ACW/25-26/044", "invoice_date", "2026-10-21", "place_of_supply", "29",
                "taxable_paise", 100000L, "cgst_paise", 9000L, "sgst_paise", 9000L, "total_paise", 118000L));  // intra → B2CS
        Map<String, Object> r = Gstr1Calculator.compute(m, FixtureGenerator.OUR_GSTIN, "29", "2026-10");
        Map<String, Object> j = (Map<String, Object>) r.get("portalJson");
        assertThat(j.get("fp")).isEqualTo("102026");
        List<Map<String, Object>> b2b = (List<Map<String, Object>>) j.get("b2b");
        assertThat(b2b).hasSize(1);
        Map<String, Object> inv = ((List<Map<String, Object>>) b2b.get(0).get("inv")).get(0);
        assertThat(inv.get("idt")).isEqualTo("05-10-2026");
        assertThat(inv.get("val")).isEqualTo(new BigDecimal("141600.00"));
        assertThat((List<?>) inv.get("itms")).hasSize(1); // both lines at 18% → one rate item
        assertThat(((List<Map<String, Object>>) j.get("exp")).get(0).get("exp_typ")).isEqualTo("WOPAY");
        assertThat((List<?>) j.get("b2cl")).hasSize(1);
        List<Map<String, Object>> b2cs = (List<Map<String, Object>>) j.get("b2cs");
        assertThat(b2cs).hasSize(1);
        assertThat(b2cs.get(0)).containsEntry("sply_ty", "INTRA").containsEntry("txval", new BigDecimal("1000.00"));
        assertThat((List<?>) r.get("hsnB2b")).hasSize(2);
        assertThat(((Map<String, Object>) r.get("documentsIssued")).get("count")).isEqualTo(4);
    }
}
