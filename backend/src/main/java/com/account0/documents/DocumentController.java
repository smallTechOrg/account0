package com.account0.documents;

import com.account0.common.ApiException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class DocumentController {
    private final DocumentService docs;
    private final FolderWatcher watcher;
    private final List<ExternalSource> sources;
    public DocumentController(DocumentService docs, FolderWatcher watcher, List<ExternalSource> sources) { this.docs = docs; this.watcher = watcher; this.sources = sources; }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("files") List<MultipartFile> files) throws Exception {
        if (files.isEmpty()) throw ApiException.bad("no files");
        long batch = docs.newBatch("UPLOAD");
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (MultipartFile f : files) {
            try { out.add(docs.ingest(f.getBytes(), Objects.requireNonNullElse(f.getOriginalFilename(), "upload"), f.getContentType(), "UPLOAD", batch)); }
            catch (ApiException e) { errors.add(e.getReason()); }
        }
        return Map.of("batchId", batch, "documents", out, "errors", errors);
    }

    @GetMapping("/documents") public List<Map<String, Object>> list(@RequestParam(required = false) String status) { return docs.list(status); }
    @GetMapping("/documents/{id}") public Map<String, Object> get(@PathVariable long id) { return docs.get(id); }
    @PostMapping("/documents/{id}/retry") public Map<String, Object> retry(@PathVariable long id) { docs.requeue(id); return docs.get(id); }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable long id) {
        Map<String, Object> d = docs.get(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) d.get("mime")))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + d.get("filename") + "\"")
                .body(new FileSystemResource(docs.file(id)));
    }

    @GetMapping("/batches/{id}") public Map<String, Object> batch(@PathVariable long id) { return docs.batch(id); }

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        List<Map<String, Object>> ext = sources.stream().map(ExternalSource::status).toList();
        return Map.of("watchedFolder", watcher.inbox().toString(), "external", ext);
    }

    @PostMapping("/sources/folder/scan") public Map<String, Object> scan() { return Map.of("imported", watcher.scan()); }
}
