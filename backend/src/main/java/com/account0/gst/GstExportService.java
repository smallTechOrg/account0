package com.account0.gst;

import com.account0.common.Tenant;
import com.account0.documents.DocumentService;
import com.account0.ledger.LedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class GstExportService {
    private final GstService gst;
    private final LedgerService ledger;
    private final DocumentService docs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public GstExportService(GstService gst, LedgerService ledger, DocumentService docs, JdbcTemplate jdbc, ObjectMapper om) {
        this.gst = gst; this.ledger = ledger; this.docs = docs; this.jdbc = jdbc; this.om = om.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public byte[] gstr1Json(String period) throws Exception { return om.writeValueAsBytes(gst.gstr1(period).get("portalJson")); }

    static BigDecimal r(Object paise) { return BigDecimal.valueOf(paise == null ? 0 : ((Number) paise).longValue(), 2); }

    @SuppressWarnings("unchecked")
    public byte[] excel(String period) throws Exception {
        Map<String, Object> g3 = gst.gstr3b(period), g1 = gst.gstr1(period);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle(); Font f = wb.createFont(); f.setBold(true); bold.setFont(f);
            CellStyle money = wb.createCellStyle(); money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            // GSTR-3B
            Sheet s = wb.createSheet("GSTR-3B");
            int rn = 0;
            row(s, rn++, bold, money, "Account0 recomputation of GSTR-3B for " + period + " (GSTIN " + g3.get("gstin") + ")");
            for (String w : (List<String>) g3.get("warnings")) row(s, rn++, null, money, "WARNING: " + w);
            rn++;
            row(s, rn++, bold, money, "Table", "Description", "Taxable value", "IGST", "CGST", "SGST", "Cess", "Rule", "Source invoices");
            for (String t : List.of("table31", "table32", "table4", "table5"))
                for (Map<String, Object> fg : (List<Map<String, Object>>) g3.get(t))
                    row(s, rn++, null, money, fg.get("key"), fg.get("label"), r(fg.get("taxable_paise")), r(fg.get("igst_paise")), r(fg.get("cgst_paise")), r(fg.get("sgst_paise")), r(fg.get("cess_paise")), fg.get("rule") + " [" + fg.get("ref") + "]",
                            String.join(", ", ((List<Map<String, Object>>) fg.get("sources")).stream().map(x -> String.valueOf(x.get("invoice_no"))).toList()));
            rn++;
            Map<String, Object> pay = (Map<String, Object>) g3.get("payment61");
            row(s, rn++, bold, money, "6.1 Payment of tax", "Head", "Liability (fwd)", "Paid by IGST ITC", "Paid by CGST ITC", "Paid by SGST ITC", "Cash (fwd)", "RCM (cash)");
            for (Map<String, Object> h : (List<Map<String, Object>>) pay.get("heads"))
                row(s, rn++, null, money, "", h.get("head"), r(h.get("liabilityPaise")), r(h.get("paidByIgstItcPaise")), r(h.get("paidByCgstItcPaise")), r(h.get("paidBySgstItcPaise")), r(h.get("cashPaise")), r(h.get("rcmCashPaise")));
            row(s, rn++, bold, money, "", "Total cash payable", r(pay.get("cashTotalPaise")));
            for (int i = 0; i < 9; i++) s.setColumnWidth(i, i == 1 ? 18000 : i == 7 ? 20000 : 4200);
            // GSTR-1 tables
            Sheet s1 = wb.createSheet("GSTR-1 summary"); rn = 0;
            row(s1, rn++, bold, money, "Table", "Description", "Taxable", "IGST", "CGST", "SGST", "Cess", "Invoices");
            for (Map<String, Object> fg : (List<Map<String, Object>>) g1.get("tables"))
                row(s1, rn++, null, money, fg.get("key"), fg.get("label"), r(fg.get("taxable_paise")), r(fg.get("igst_paise")), r(fg.get("cgst_paise")), r(fg.get("sgst_paise")), r(fg.get("cess_paise")), ((List<?>) fg.get("sources")).size());
            s1.setColumnWidth(1, 16000);
            for (String key : List.of("hsnB2b", "hsnB2c")) {
                Sheet h = wb.createSheet("HSN " + (key.equals("hsnB2b") ? "B2B" : "B2C")); int hr = 0;
                row(h, hr++, bold, money, "HSN/SAC", "Description", "UQC", "Qty", "Rate", "Taxable", "IGST", "CGST", "SGST", "Cess");
                for (Map<String, Object> x : (List<Map<String, Object>>) g1.get(key))
                    row(h, hr++, null, money, x.get("hsn_sc"), x.get("desc"), x.get("uqc"), x.get("qty"), x.get("rt"), x.get("txval"), x.get("iamt"), x.get("camt"), x.get("samt"), x.get("csamt"));
            }
            // invoice register
            Sheet reg = wb.createSheet("Invoice register"); rn = 0;
            row(reg, rn++, bold, money, "Id", "Date", "Direction", "Type", "Invoice no", "Party", "GSTIN", "POS", "Supply type", "RCM", "ITC", "Taxable", "IGST", "CGST", "SGST", "Cess", "Total", "Category");
            for (Map<String, Object> i : gst.confirmed(period))
                row(reg, rn++, null, money, i.get("id"), i.get("invoice_date"), i.get("direction"), i.get("doc_type"), i.get("invoice_no"), i.get("counterparty_name"), i.get("counterparty_gstin"), i.get("place_of_supply"),
                        i.get("supply_type"), i.get("reverse_charge"), i.get("itc_eligible"), r(i.get("taxable_paise")), r(i.get("igst_paise")), r(i.get("cgst_paise")), r(i.get("sgst_paise")), r(i.get("cess_paise")), r(i.get("total_paise")), i.get("category"));
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    static void row(Sheet s, int n, CellStyle style, CellStyle money, Object... vals) {
        Row row = s.createRow(n);
        for (int i = 0; i < vals.length; i++) {
            Cell c = row.createCell(i);
            Object v = vals[i];
            if (v instanceof BigDecimal b) { c.setCellValue(b.doubleValue()); c.setCellStyle(money); }
            else if (v instanceof Number num) c.setCellValue(num.doubleValue());
            else c.setCellValue(v == null ? "" : v.toString());
            if (style != null) c.setCellStyle(style);
        }
    }

    public String registerCsv(String period) {
        StringBuilder sb = new StringBuilder("id,invoice_date,direction,doc_type,invoice_no,party,gstin,place_of_supply,supply_type,reverse_charge,itc_eligible,taxable,igst,cgst,sgst,cess,total,category,document\n");
        for (Map<String, Object> i : gst.confirmed(period))
            sb.append(String.join(",", List.of(s(i.get("id")), s(i.get("invoice_date")), s(i.get("direction")), s(i.get("doc_type")), LedgerService.csv(i.get("invoice_no")), LedgerService.csv(i.get("counterparty_name")),
                    s(i.get("counterparty_gstin")), s(i.get("place_of_supply")), s(i.get("supply_type")), s(i.get("reverse_charge")), s(i.get("itc_eligible")),
                    r(i.get("taxable_paise")).toPlainString(), r(i.get("igst_paise")).toPlainString(), r(i.get("cgst_paise")).toPlainString(), r(i.get("sgst_paise")).toPlainString(),
                    r(i.get("cess_paise")).toPlainString(), r(i.get("total_paise")).toPlainString(), LedgerService.csv(i.get("category")), s(i.get("document_id"))))).append('\n');
        return sb.toString();
    }
    static String s(Object o) { return o == null ? "" : o.toString(); }

    /** Monthly CA pack: everything the CA needs to file + verify, in one zip. */
    @SuppressWarnings("unchecked")
    public byte[] caPack(String period) throws Exception {
        Map<String, Object> g3 = gst.gstr3b(period);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            List<String> warnings = (List<String>) g3.get("warnings");
            put(z, "README.txt", ("Account0 CA pack for " + period + " (GSTIN " + g3.get("gstin") + ")\n\n"
                    + "gstr3b-computation.json  Account0's deterministic recomputation of GSTR-3B, every figure with rule + source invoices\n"
                    + "gstr1-portal.json        GSTR-1 in GST offline-tool JSON shape (best effort — validate in the offline tool before upload)\n"
                    + "gst-workings.xlsx        3B + GSTR-1 summaries, HSN, invoice register\n"
                    + "invoice-register.csv     Confirmed invoices of the period\n"
                    + "journal.csv / trial-balance.csv  Books for the period\n"
                    + "documents/               Source invoices (as uploaded)\n\n"
                    + (warnings.isEmpty() ? "No warnings.\n" : "WARNINGS:\n- " + String.join("\n- ", warnings) + "\n")).getBytes());
            put(z, "gstr3b-computation.json", om.writeValueAsBytes(g3));
            put(z, "gstr1-portal.json", gstr1Json(period));
            put(z, "gst-workings.xlsx", excel(period));
            put(z, "invoice-register.csv", registerCsv(period).getBytes());
            put(z, "journal.csv", ledger.journalCsv(period).getBytes());
            Map<String, Object> tb = ledger.trialBalance(period);
            StringBuilder tbs = new StringBuilder("account,debit,credit\n");
            for (Map<String, Object> a : (List<Map<String, Object>>) tb.get("accounts")) tbs.append(LedgerService.csv(a.get("account"))).append(',').append(r(a.get("debit_paise"))).append(',').append(r(a.get("credit_paise"))).append('\n');
            put(z, "trial-balance.csv", tbs.toString().getBytes());
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> i : gst.confirmed(period)) {
                if (i.get("document_id") == null) continue;
                Map<String, Object> d = docs.get(((Number) i.get("document_id")).longValue());
                String name = "documents/" + i.get("direction").toString().toLowerCase() + "-" + String.valueOf(i.get("invoice_no")).replaceAll("[^A-Za-z0-9._-]", "_") + "-" + d.get("filename");
                if (!seen.add(name)) continue;
                Path p = docs.file(((Number) d.get("id")).longValue());
                if (Files.exists(p)) put(z, name, Files.readAllBytes(p));
            }
        }
        jdbc.update("INSERT INTO audit_log(tenant_id, actor, entity, entity_id, action, note) VALUES (?,?,?,?,?,?)", Tenant.current(), "user", "ca_pack", period, "export", "CA pack generated");
        return bos.toByteArray();
    }
    static void put(ZipOutputStream z, String name, byte[] b) throws Exception { z.putNextEntry(new ZipEntry(name)); z.write(b); z.closeEntry(); }
}
