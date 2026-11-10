package com.account0.scrutiny;

import com.account0.common.ApiException;
import com.account0.llm.LlmException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scrutiny")
public class ScrutinyController {
    private final ScrutinyService svc;
    public ScrutinyController(ScrutinyService svc) { this.svc = svc; }

    @GetMapping public List<Map<String, Object>> list() { return svc.list(); }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        String name = String.valueOf(file.getOriginalFilename()).toLowerCase();
        long id;
        if (name.endsWith(".json")) id = svc.importPortalJson(file.getBytes());
        else if (name.endsWith(".pdf")) {
            try { id = svc.importPdf(file.getBytes(), file.getOriginalFilename()); }
            catch (LlmException e) { throw ApiException.bad(e.getMessage()); }
        } else throw ApiException.bad("Upload the GSTR-3B JSON downloaded from the portal, or the filed 3B PDF.");
        return svc.report(id);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/manual")
    public Map<String, Object> manual(@RequestBody Map<String, Object> body) throws Exception {
        String period = (String) body.get("period");
        if (period == null || !period.matches("\\d{4}-\\d{2}")) throw ApiException.bad("period YYYY-MM required");
        return svc.report(svc.importManual(period, (Map<String, Map<String, Object>>) body.get("figures")));
    }

    @GetMapping("/{id}") public Map<String, Object> report(@PathVariable long id) throws Exception { return svc.report(id); }
    @PostMapping("/{id}/confirm") public Map<String, Object> confirm(@PathVariable long id) throws Exception { svc.confirm(id); return svc.report(id); }
    @PostMapping("/{id}/explain")
    public Map<String, Object> explain(@PathVariable long id) throws Exception {
        try { return Map.of("explanation", svc.explain(id)); } catch (LlmException e) { throw ApiException.bad(e.getMessage()); }
    }
}
