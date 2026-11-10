package com.account0.invoice;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class InvoiceController {
    private final InvoiceService svc;
    private final RuleService rules;
    public InvoiceController(InvoiceService svc, RuleService rules) { this.svc = svc; this.rules = rules; }

    @GetMapping("/invoices")
    public List<Map<String, Object>> list(@RequestParam(required = false) String period, @RequestParam(required = false) String status, @RequestParam(required = false) String direction) {
        return svc.list(period, status, direction);
    }
    @GetMapping("/invoices/{id}") public Map<String, Object> get(@PathVariable long id) { return svc.get(id); }

    /** Manual entry (no source document). */
    @PostMapping("/invoices")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> lines = (List<Map<String, Object>>) body.getOrDefault("lines", List.of());
        long id = svc.createDraft(null, body, new ArrayList<>(lines.stream().map(LinkedHashMap::new).toList()), List.of(), "user");
        // Manual entry: the user typed the treatment themselves, so apply it.
        Map<String, Object> t = new LinkedHashMap<>();
        for (String k : List.of("supply_type", "reverse_charge", "itc_eligible")) if (body.containsKey(k)) t.put(k, body.get(k));
        if (!t.isEmpty()) svc.update(id, t, "user");
        return svc.get(id);
    }

    @PutMapping("/invoices/{id}") public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) { return svc.update(id, body, "user"); }
    @PostMapping("/invoices/{id}/compute-rcm") public Map<String, Object> rcm(@PathVariable long id) { return svc.computeRcm(id); }
    @PostMapping("/invoices/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return svc.confirm(id, Boolean.TRUE.equals(b.get("saveRule")), Boolean.TRUE.equals(b.get("acknowledgeWarnings")));
    }
    @PostMapping("/invoices/{id}/reopen") public Map<String, Object> reopen(@PathVariable long id) { return svc.reopen(id); }
    @PostMapping("/invoices/{id}/cancel") public Map<String, Object> cancel(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return svc.cancel(id, body == null ? null : (String) body.get("reason"));
    }
    @PostMapping("/invoices/{id}/reject") public Map<String, Object> reject(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return svc.reject(id, body == null ? null : (String) body.get("reason"));
    }

    @GetMapping("/supply-types")
    public List<Map<String, String>> supplyTypes() {
        return Arrays.stream(SupplyType.values()).map(t -> Map.of("value", t.name(), "direction", t.direction, "label", t.label, "rule", t.rule)).toList();
    }

    @GetMapping("/rules") public List<Map<String, Object>> rules() { return rules.list(); }
    @DeleteMapping("/rules/{id}") public Map<String, Object> deleteRule(@PathVariable long id) { rules.delete(id); return Map.of("deleted", id); }
}
