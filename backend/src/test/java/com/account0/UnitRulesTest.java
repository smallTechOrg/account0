package com.account0;

import com.account0.company.Gstin;
import com.account0.fixtures.FixtureGenerator;
import com.account0.invoice.Flag;
import com.account0.invoice.InvoiceChecks;
import com.account0.ledger.LedgerService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class UnitRulesTest {
    @Test void gstinChecksum() {
        assertThat(Gstin.isValid(FixtureGenerator.OUR_GSTIN)).isTrue();
        assertThat(Gstin.isValid("29AAKCA1234B1Z5")).isFalse();
        assertThat(Gstin.isValid("27AAPFU0939F1ZV")).isTrue(); // widely published sample GSTIN
        assertThat(Gstin.pan(FixtureGenerator.OUR_GSTIN)).isEqualTo("AAKCA1234B");
    }

    static Map<String, Object> inv(Object... kv) { Map<String, Object> m = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }

    @Test void structuralChecksCatchMismatches() {
        Map<String, Object> i = inv("invoice_no", "X1", "invoice_date", "2026-10-05", "direction", "PURCHASE", "counterparty_gstin", FixtureGenerator.VENDOR_GSTIN,
                "place_of_supply", "29", "taxable_paise", 5000000L, "igst_paise", 900000L, "cgst_paise", 0L, "sgst_paise", 0L, "total_paise", 5900000L);
        assertThat(InvoiceChecks.structural(i, List.of(), FixtureGenerator.OUR_GSTIN, LocalDate.of(2026, 11, 10))).isEmpty();
        i.put("total_paise", 6000000L);
        assertThat(InvoiceChecks.structural(i, List.of(), FixtureGenerator.OUR_GSTIN, LocalDate.of(2026, 11, 10))).extracting(Flag::code).contains("TOTAL_MISMATCH");
        i.put("total_paise", 5900000L); i.put("igst_paise", 0L); i.put("cgst_paise", 450000L); i.put("sgst_paise", 450000L);
        // supplier in 27, POS 29 → inter-state; CGST+SGST is wrong
        assertThat(InvoiceChecks.structural(i, List.of(), FixtureGenerator.OUR_GSTIN, LocalDate.of(2026, 11, 10))).extracting(Flag::code).contains("CGST_ON_INTER");
    }

    @Test void confirmRequiresUserTreatment() {
        Map<String, Object> i = inv("direction", "SALE", "taxable_paise", 100L);
        assertThat(InvoiceChecks.forConfirm(i)).extracting(Flag::code).contains("TREATMENT_UNCONFIRMED");
        i.put("supply_type", "EXPORT_LUT"); i.put("reverse_charge", 0L); i.put("igst_paise", 1800L);
        assertThat(InvoiceChecks.forConfirm(i)).extracting(Flag::code).contains("TAX_ON_ZERO_TYPE");
        i.put("supply_type", "IMPORT_SERVICE_RCM"); i.put("direction", "PURCHASE"); i.put("itc_eligible", 1L); i.put("reverse_charge", 0L);
        assertThat(InvoiceChecks.forConfirm(i)).extracting(Flag::code).contains("RCM_INCONSISTENT");
    }

    @Test void ledgerBalancesForEveryShape() {
        List<Map<String, Object>> cases = List.of(
                inv("direction", "SALE", "supply_type", "B2B", "taxable_paise", 12000000L, "cgst_paise", 1080000L, "sgst_paise", 1080000L, "total_paise", 14160000L, "counterparty_name", "B"),
                inv("direction", "SALE", "supply_type", "EXPORT_LUT", "taxable_paise", 33600000L, "total_paise", 33600000L, "counterparty_name", "N"),
                inv("direction", "PURCHASE", "supply_type", "DOMESTIC", "itc_eligible", 1L, "taxable_paise", 5000000L, "igst_paise", 900000L, "total_paise", 5900000L, "counterparty_name", "V"),
                inv("direction", "PURCHASE", "supply_type", "DOMESTIC", "itc_eligible", 0L, "taxable_paise", 5000000L, "igst_paise", 900000L, "total_paise", 5900000L, "counterparty_name", "V"),
                inv("direction", "PURCHASE", "supply_type", "IMPORT_SERVICE_RCM", "itc_eligible", 1L, "taxable_paise", 10080000L, "igst_paise", 1814400L, "total_paise", 10080000L, "counterparty_name", "A"),
                inv("direction", "SALE", "doc_type", "CREDIT_NOTE", "supply_type", "B2B", "taxable_paise", 1000L, "igst_paise", 180L, "total_paise", 1180L, "counterparty_name", "B"));
        for (Map<String, Object> c : cases) {
            List<LedgerService.Line> ls = LedgerService.linesFor(c);
            assertThat(ls.stream().mapToLong(LedgerService.Line::debit).sum()).as(c.toString()).isEqualTo(ls.stream().mapToLong(LedgerService.Line::credit).sum());
            assertThat(ls).noneMatch(l -> l.account().equals("Round off"));
        }
        List<LedgerService.Line> rcm = LedgerService.linesFor(cases.get(4));
        assertThat(rcm).extracting(LedgerService.Line::account).contains("Input IGST (RCM)", "RCM IGST payable");
    }
}
