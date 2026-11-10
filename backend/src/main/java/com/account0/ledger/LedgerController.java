package com.account0.ledger;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {
    private final LedgerService svc;
    public LedgerController(LedgerService svc) { this.svc = svc; }
    @GetMapping("/journal") public List<Map<String, Object>> journal(@RequestParam(required = false) String period) { return svc.journal(period); }
    @GetMapping("/trial-balance") public Map<String, Object> tb(@RequestParam(required = false) String period) { return svc.trialBalance(period); }
    @GetMapping("/journal.csv")
    public ResponseEntity<String> csv(@RequestParam(required = false) String period) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"journal-" + (period == null ? "all" : period) + ".csv\"").body(svc.journalCsv(period));
    }
}
