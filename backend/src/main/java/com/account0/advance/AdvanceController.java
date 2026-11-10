package com.account0.advance;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/advances")
public class AdvanceController {
    private final AdvanceService svc;
    public AdvanceController(AdvanceService svc) { this.svc = svc; }
    @GetMapping public List<Map<String, Object>> list() { return svc.list(); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> b) { return svc.create(b); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable long id) { return svc.get(id); }
    @PostMapping("/{id}/delete") public Map<String, Object> delete(@PathVariable long id, @RequestBody(required = false) Map<String, Object> b) { return svc.delete(id, b == null ? null : (String) b.get("reason")); }
    @PostMapping("/{id}/adjust") public Map<String, Object> adjust(@PathVariable long id, @RequestBody Map<String, Object> b) {
        return svc.adjust(id, ((Number) b.get("invoiceId")).longValue(), b.get("grossPaise") == null ? null : ((Number) b.get("grossPaise")).longValue());
    }
    @PostMapping("/adjustments/{id}/remove") public Map<String, Object> unadjust(@PathVariable long id) { return svc.removeAdjustment(id); }
}
