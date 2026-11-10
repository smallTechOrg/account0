package com.account0.company;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/company")
public class CompanyController {
    private final CompanyService svc; private final com.account0.extraction.ExtractionService extraction;
    public CompanyController(CompanyService svc, com.account0.extraction.ExtractionService extraction) { this.svc = svc; this.extraction = extraction; }
    @GetMapping public Map<String, Object> get() { return svc.get(); }
    @PutMapping public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(svc.update(body));
        out.put("remapped_drafts", extraction.remapUnknownDirections());   // drafts whose direction is now known from the new GSTIN/name
        return out;
    }
    @GetMapping("/states") public Map<String, String> states() { return new java.util.TreeMap<>(Gstin.STATES); }
}
