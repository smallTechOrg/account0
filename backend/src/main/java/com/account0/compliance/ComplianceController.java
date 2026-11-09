package com.account0.compliance;

import com.account0.common.ApiException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ComplianceController {
    private final ComplianceService svc; private final CloseService close;
    public ComplianceController(ComplianceService svc, CloseService close) { this.svc = svc; this.close = close; }

    private static LocalDate date(String s) { try { return s == null ? LocalDate.now() : LocalDate.parse(s); } catch (Exception e) { throw ApiException.bad("asOf must be YYYY-MM-DD"); } }

    @GetMapping("/compliance/calendar")
    public Map<String, Object> calendar(@RequestParam(required = false) String fy, @RequestParam(required = false) String asOf) {
        LocalDate d = date(asOf); return svc.calendar(fy == null ? ComplianceService.currentFy(d) : fy, d);
    }
    @PostMapping("/compliance/mark")
    public Map<String, Object> mark(@RequestBody Map<String, String> b) { return svc.mark(b.get("code"), b.get("period"), b.get("status"), b.get("done_on"), b.get("ref"), b.get("note")); }
    @PostMapping("/compliance/catch-up")
    public Map<String, Object> catchUp(@RequestBody Map<String, String> b) { return svc.catchUp(b.get("fy"), date(b.get("before"))); }

    @GetMapping("/close/{period}") public Map<String, Object> checklist(@PathVariable String period) { return close.checklist(period); }
    @PostMapping("/close/{period}/tick")
    public Map<String, Object> tick(@PathVariable String period, @RequestBody Map<String, String> b) { return close.tick(period, b.get("key"), b.get("state"), b.get("note")); }
}
