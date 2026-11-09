package com.account0.bank;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/bank")
public class BankController {
    private final BankService svc;
    public BankController(BankService svc) { this.svc = svc; }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> preview(@RequestParam("file") MultipartFile f) throws Exception { return svc.preview(f.getBytes(), f.getOriginalFilename()); }

    /** Optional column overrides as 0-based indexes: date, description, ref, debit, credit, amount, type, balance. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importFile(@RequestParam("file") MultipartFile f, @RequestParam(required = false) String account, @RequestParam Map<String, String> params) throws Exception {
        BankParser.Mapping auto = BankParser.detect(BankParser.read(f.getBytes(), f.getOriginalFilename()).headers());
        BankParser.Mapping m = params.keySet().stream().anyMatch(k -> List.of("date", "description", "ref", "debit", "credit", "amount", "type", "balance").contains(k))
                ? new BankParser.Mapping(i(params, "date", auto.date()), i(params, "description", auto.description()), i(params, "ref", auto.ref()), i(params, "debit", auto.debit()), i(params, "credit", auto.credit()), i(params, "amount", auto.amount()), i(params, "type", auto.type()), i(params, "balance", auto.balance())) : null;
        return svc.importStatement(f.getBytes(), f.getOriginalFilename(), account, m);
    }
    private static int i(Map<String, String> p, String k, int d) { return p.containsKey(k) ? Integer.parseInt(p.get(k)) : d; }

    @GetMapping("/txns") public List<Map<String, Object>> txns(@RequestParam(required = false) String status) { return svc.txns(status); }
    @GetMapping("/summary") public Map<String, Object> summary() { return svc.summary(); }
    @GetMapping("/txns/{id}/candidates") public List<Map<String, Object>> candidates(@PathVariable long id) { return svc.candidates(id); }
    @PostMapping("/txns/{id}/match") public Map<String, Object> match(@PathVariable long id, @RequestBody Map<String, Object> b) { return svc.match(id, ((Number) b.get("invoiceId")).longValue()); }
    @PostMapping("/txns/{id}/unmatch") public Map<String, Object> unmatch(@PathVariable long id) { return svc.unmatch(id); }
    @PostMapping("/txns/{id}/ignore") public Map<String, Object> ignore(@PathVariable long id, @RequestBody Map<String, String> b) { return svc.ignore(id, b.get("reason")); }
    @PostMapping("/rematch") public Map<String, Object> rematch() { return svc.autoMatch(); }
    @GetMapping("/statements/{id}/balance-check") public Map<String, Object> bal(@PathVariable long id) { return svc.balanceCheck(id); }
}
