package com.account0.itc;

import com.account0.common.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ItcController {
    private final ItcService itc; private final AlertService alerts;
    public ItcController(ItcService itc, AlertService alerts) { this.itc = itc; this.alerts = alerts; }

    @PostMapping(value = "/itc/2b/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile f) throws Exception {
        if (f.isEmpty()) throw ApiException.bad("Empty file");
        return itc.importJson(f.getBytes(), f.getOriginalFilename());
    }
    @GetMapping("/itc/2b/imports") public List<Map<String, Object>> imports() { return itc.imports(); }
    @GetMapping("/itc/match") public Map<String, Object> match(@RequestParam String period) { return itc.report(period); }
    @GetMapping("/alerts") public List<Map<String, Object>> alerts() { return alerts.alerts(); }
}
