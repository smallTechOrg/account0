package com.account0.company;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/company")
public class CompanyController {
    private final CompanyService svc;
    public CompanyController(CompanyService svc) { this.svc = svc; }
    @GetMapping public Map<String, Object> get() { return svc.get(); }
    @PutMapping public Map<String, Object> put(@RequestBody Map<String, Object> body) { return svc.update(body); }
    @GetMapping("/states") public Map<String, String> states() { return new java.util.TreeMap<>(Gstin.STATES); }
}
