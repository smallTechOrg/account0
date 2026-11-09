package com.account0.gst;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GstController {
    private final GstService gst;
    private final GstExportService exports;
    public GstController(GstService gst, GstExportService exports) { this.gst = gst; this.exports = exports; }

    @GetMapping("/gst/3b") public Map<String, Object> g3b(@RequestParam String period) { return gst.gstr3b(period); }
    @GetMapping("/gst/1") public Map<String, Object> g1(@RequestParam String period) { return gst.gstr1(period); }

    @GetMapping("/gst/1/portal.json")
    public ResponseEntity<byte[]> g1json(@RequestParam String period) throws Exception {
        return file(exports.gstr1Json(period), MediaType.APPLICATION_JSON, "GSTR1-" + period + ".json");
    }
    @GetMapping("/gst/workings.xlsx")
    public ResponseEntity<byte[]> xlsx(@RequestParam String period) throws Exception {
        return file(exports.excel(period), MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), "gst-workings-" + period + ".xlsx");
    }
    @GetMapping("/gst/ca-pack.zip")
    public ResponseEntity<byte[]> pack(@RequestParam String period) throws Exception {
        return file(exports.caPack(period), MediaType.parseMediaType("application/zip"), "account0-ca-pack-" + period + ".zip");
    }
    static ResponseEntity<byte[]> file(byte[] b, MediaType t, String name) {
        return ResponseEntity.ok().contentType(t).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(b);
    }

    @GetMapping("/filings") public List<Map<String, Object>> filings() { return gst.filings(); }
    @PostMapping("/filings") public Map<String, Object> record(@RequestBody Map<String, Object> b) { return gst.recordFiling(b); }
    @PostMapping("/filings/{id}/unlock") public Map<String, Object> unlock(@PathVariable long id, @RequestBody Map<String, Object> b) { return gst.setLock(id, false, (String) b.get("reason")); }
    @PostMapping("/filings/{id}/lock") public Map<String, Object> lock(@PathVariable long id) { return gst.setLock(id, true, "relocked"); }
}
