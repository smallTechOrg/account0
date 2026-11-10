package com.account0.tds;

import com.account0.company.CompanyService;
import com.account0.payroll.PayrollService;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class TdsController {
    private final TdsService tds; private final PayrollService payroll; private final CompanyService company;
    public TdsController(TdsService tds, PayrollService payroll, CompanyService company) { this.tds = tds; this.payroll = payroll; this.company = company; }

    @GetMapping("/tds/sections") public Collection<TdsSections.Section> sections() { return TdsSections.ALL.values(); }
    /** Form names for a FY (Income-tax Rules 2026 renumbering) and a caveat about the new s.393 payment codes. */
    @GetMapping("/tds/forms")
    public Map<String, Object> forms(@RequestParam String fy) {
        return Map.of("fy", fy, "nonSalaryReturn", TdsForms.nonSalaryReturn(fy), "salaryReturn", TdsForms.salaryReturn(fy), "nonResidentReturn", TdsForms.nonResidentReturn(fy),
                "salaryCertificate", TdsForms.salaryCertificate(fy), "nonSalaryCertificate", TdsForms.nonSalaryCertificate(fy),
                "codesNote", "From 1 Apr 2026 TDS payments are reported under s.393 Income-tax Act 2025 with numeric payment codes (e.g. 1008/1009 rent, 1028 director fees). Codes shown beside sections come from secondary sources — verify with CA before filing.");
    }
    @GetMapping("/tds/pending") public List<Map<String, Object>> pending() { return tds.pending(); }
    @PostMapping("/tds/assign")
    public Map<String, Object> assign(@RequestBody Map<String, Object> b) {
        return tds.assign(((Number) b.get("invoiceId")).longValue(), (String) b.get("section"), (String) b.get("payeeType"), (String) b.get("pan"), Boolean.TRUE.equals(b.get("saveRule")));
    }
    @DeleteMapping("/tds/assign/{invoiceId}") public Map<String, Object> unassign(@PathVariable long invoiceId) { tds.remove(invoiceId); return Map.of("ok", true); }
    @GetMapping("/tds/register")
    public Map<String, Object> register(@RequestParam String fy, @RequestParam(required = false) Integer quarter) {
        List<Map<String, Object>> rows = tds.register(fy, quarter);
        long total = rows.stream().mapToLong(r -> ((Number) r.get("tds_paise")).longValue()).sum();
        return Map.of("fy", fy, "rows", rows, "totalTdsPaise", total, "thresholds", tds.thresholds(fy));
    }
    @PostMapping("/tds/{id}/deposit")
    public Map<String, Object> deposit(@PathVariable long id, @RequestBody Map<String, String> b) { return tds.deposit(id, b.get("challan_no"), b.get("bsr_code"), b.get("deposited_on")); }
    @GetMapping(value = "/tds/26q.csv", produces = "text/csv")
    public ResponseEntity<byte[]> q26(@RequestParam String fy, @RequestParam int quarter) {
        return csv(TdsForms.fileTag(fy, "26Q", "Form140") + "-" + fy + "-Q" + quarter + ".csv", "TAN," + Objects.toString(company.get().get("tan"), "") + "\n" + tds.csv26q(fy, quarter));
    }

    @PostMapping(value = "/payroll/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importPayroll(@RequestParam("file") MultipartFile f) throws Exception { return payroll.importCsv(f.getBytes()); }
    @GetMapping("/payroll")
    public Map<String, Object> payroll(@RequestParam String fy) { return Map.of("fy", fy, "lines", payroll.lines(fy), "months", payroll.monthSummary(fy)); }
    @PostMapping("/payroll/deposit")
    public Map<String, Object> payrollDeposit(@RequestBody Map<String, String> b) { return payroll.deposit(b.get("month"), b.get("challan_no"), b.get("deposited_on")); }
    @GetMapping(value = "/payroll/24q.csv", produces = "text/csv")
    public ResponseEntity<byte[]> q24(@RequestParam String fy, @RequestParam int quarter) {
        return csv(TdsForms.fileTag(fy, "24Q", "Form138") + "-" + fy + "-Q" + quarter + ".csv", "TAN," + Objects.toString(company.get().get("tan"), "") + "\n" + payroll.csv24q(fy, quarter));
    }
    private static ResponseEntity<byte[]> csv(String name, String body) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").contentType(MediaType.parseMediaType("text/csv")).body(body.getBytes(StandardCharsets.UTF_8));
    }
}
