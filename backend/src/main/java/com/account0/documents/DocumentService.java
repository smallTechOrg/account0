package com.account0.documents;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DocumentService {
    public static final List<String> ALLOWED = List.of("application/pdf", "image/png", "image/jpeg", "image/webp", "image/gif");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Path docDir;

    public DocumentService(JdbcTemplate jdbc, AuditService audit, ApplicationEventPublisher events, @Value("${account0.data-dir}") String dataDir) {
        this.jdbc = jdbc; this.audit = audit; this.events = events;
        this.docDir = Path.of(dataDir).toAbsolutePath().normalize().resolve("documents");
    }

    public long newBatch(String source) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> { PreparedStatement ps = c.prepareStatement("INSERT INTO doc_batch(tenant_id, source) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, Tenant.current()); ps.setString(2, source); return ps; }, kh);
        return kh.getKey().longValue();
    }

    public static String mimeFor(String filename, String declared) {
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".pdf")) return "application/pdf";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".webp")) return "image/webp";
        return declared == null ? "application/octet-stream" : declared;
    }

    /** Stores the file and queues extraction. Exact duplicates (same SHA-256) are recorded as DUPLICATE and not re-extracted. */
    public Map<String, Object> ingest(byte[] bytes, String filename, String mime, String source, Long batchId) {
        mime = mimeFor(filename, mime);
        if (!ALLOWED.contains(mime)) throw ApiException.bad("Unsupported file type " + mime + " (" + filename + "). Use PDF, PNG or JPG.");
        if (bytes.length == 0) throw ApiException.bad("Empty file " + filename);
        String sha = sha256(bytes);
        List<Long> existing = jdbc.queryForList("SELECT id FROM document WHERE tenant_id=? AND sha256=? AND status NOT IN ('DUPLICATE','REJECTED') ORDER BY id LIMIT 1", Long.class, Tenant.current(), sha);
        try {
            Files.createDirectories(docDir);
            String ext = mime.equals("application/pdf") ? ".pdf" : "." + mime.substring(6);
            Path p = docDir.resolve(sha + ext);
            if (!Files.exists(p)) Files.write(p, bytes);
            String status = existing.isEmpty() ? "QUEUED" : "DUPLICATE";
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            final String m = mime;
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("INSERT INTO document(tenant_id,batch_id,filename,mime,sha256,stored_path,size_bytes,source,status,duplicate_of,error) VALUES (?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, Tenant.current()); if (batchId == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, batchId);
                ps.setString(3, filename); ps.setString(4, m); ps.setString(5, sha); ps.setString(6, p.toString()); ps.setLong(7, bytes.length);
                ps.setString(8, source); ps.setString(9, status);
                if (existing.isEmpty()) ps.setNull(10, java.sql.Types.INTEGER); else ps.setLong(10, existing.get(0));
                ps.setString(11, existing.isEmpty() ? null : "Same file already uploaded as document #" + existing.get(0));
                return ps; }, kh);
            long id = kh.getKey().longValue();
            audit.record("user", "document", id, "ingest", null, null, filename, source);
            if (existing.isEmpty()) events.publishEvent(new DocumentQueued(id));
            return get(id);
        } catch (java.io.IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not store file: " + e.getMessage());
        }
    }

    /** Stores a reference document (e.g. a filed return PDF): source/status REFERENCE, never queued for invoice extraction. */
    public Map<String, Object> ingestReference(byte[] bytes, String filename) {
        String mime = mimeFor(filename, null);
        if (!ALLOWED.contains(mime)) throw ApiException.bad("Unsupported file type " + mime);
        if (bytes.length == 0) throw ApiException.bad("Empty file " + filename);
        String sha = sha256(bytes);
        try {
            Files.createDirectories(docDir);
            String ext = mime.equals("application/pdf") ? ".pdf" : "." + mime.substring(6);
            Path p = docDir.resolve(sha + ext);
            if (!Files.exists(p)) Files.write(p, bytes);
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("INSERT INTO document(tenant_id,filename,mime,sha256,stored_path,size_bytes,source,status) VALUES (?,?,?,?,?,?,'REFERENCE','REFERENCE')", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, Tenant.current()); ps.setString(2, filename); ps.setString(3, mime); ps.setString(4, sha); ps.setString(5, p.toString()); ps.setLong(6, bytes.length);
                return ps; }, kh);
            long id = kh.getKey().longValue();
            audit.record("user", "document", id, "ingest", null, null, filename, "REFERENCE");
            return get(id);
        } catch (java.io.IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not store file: " + e.getMessage());
        }
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("""
                SELECT d.id, d.batch_id, d.filename, d.mime, d.sha256, d.size_bytes, d.source, d.status, d.error, d.duplicate_of, d.created_at,
                       (SELECT i.id FROM invoice i WHERE i.document_id=d.id ORDER BY i.id DESC LIMIT 1) AS invoice_id
                FROM document d WHERE d.tenant_id=? AND d.id=?""", Tenant.current(), id);
        if (r.isEmpty()) throw ApiException.notFound("document " + id);
        return r.get(0);
    }

    public List<Map<String, Object>> list(String status) {
        String base = """
                SELECT d.id, d.batch_id, d.filename, d.mime, d.source, d.status, d.error, d.created_at,
                       (SELECT i.id FROM invoice i WHERE i.document_id=d.id ORDER BY i.id DESC LIMIT 1) AS invoice_id
                FROM document d WHERE d.tenant_id=?""";
        if (status == null) return jdbc.queryForList(base + " ORDER BY d.id DESC LIMIT 500", Tenant.current());
        return jdbc.queryForList(base + " AND d.status=? ORDER BY d.id DESC LIMIT 500", Tenant.current(), status);
    }

    public Path file(long id) {
        return Path.of(jdbc.queryForObject("SELECT stored_path FROM document WHERE tenant_id=? AND id=?", String.class, Tenant.current(), id));
    }

    public void setStatus(long id, String status, String error) {
        jdbc.update("UPDATE document SET status=?, error=? WHERE tenant_id=? AND id=?", status, error, Tenant.current(), id);
    }

    public Map<String, Object> batch(long id) {
        List<Map<String, Object>> docs = jdbc.queryForList("SELECT id, filename, status, error, (SELECT i.id FROM invoice i WHERE i.document_id=d.id ORDER BY i.id DESC LIMIT 1) AS invoice_id FROM document d WHERE tenant_id=? AND batch_id=? ORDER BY id", Tenant.current(), id);
        long done = docs.stream().filter(d -> !List.of("QUEUED", "EXTRACTING").contains(d.get("status"))).count();
        return Map.of("batchId", id, "total", docs.size(), "done", done, "complete", done == docs.size(), "documents", docs);
    }

    public void requeue(long id) {
        setStatus(id, "QUEUED", null);
        events.publishEvent(new DocumentQueued(id));
    }

    public List<Long> pending() {
        return jdbc.queryForList("SELECT id FROM document WHERE tenant_id=? AND status IN ('QUEUED','EXTRACTING') ORDER BY id", Long.class, Tenant.current());
    }

    public static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record DocumentQueued(long documentId) {}
}
