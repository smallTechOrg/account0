package com.account0.google;

import com.account0.common.SettingsService;
import com.account0.common.Tenant;
import com.account0.documents.DocumentService;
import com.account0.documents.ExternalSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** Gmail label + Drive folder pollers. Both feed the normal inbox pipeline (one extraction call per document); duplicates are skipped by external id and by file SHA-256. */
@Component
public class GoogleSources {
    private static final Logger log = LoggerFactory.getLogger(GoogleSources.class);
    static final Set<String> MIME = Set.of("application/pdf", "image/png", "image/jpeg", "image/webp");
    private final GoogleAuthService auth; private final GoogleTransport transport; private final DocumentService docs; private final JdbcTemplate jdbc; private final SettingsService settings;
    private final GoogleProperties props;

    public GoogleSources(GoogleAuthService auth, GoogleTransport transport, DocumentService docs, JdbcTemplate jdbc, SettingsService settings, GoogleProperties props) {
        this.auth = auth; this.transport = transport; this.docs = docs; this.jdbc = jdbc; this.settings = settings; this.props = props;
    }

    private boolean seen(String source, String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM external_seen WHERE tenant_id=? AND source=? AND external_id=?", Integer.class, Tenant.current(), source, id);
        return n != null && n > 0;
    }
    private void mark(String source, String id, String filename, Object docId) {
        jdbc.update("INSERT OR IGNORE INTO external_seen(tenant_id, source, external_id, filename, document_id) VALUES (?,?,?,?,?)", Tenant.current(), source, id, filename, docId);
    }

    public synchronized int syncGmail() {
        if (!auth.connected()) return 0;
        String label = settings.get("google.gmail_label", "Account0/Invoices");
        long since = Long.parseLong(settings.get("google.gmail_last_sync_epoch", "0"));
        long started = Instant.now().getEpochSecond();
        int n = 0; Long batch = null;
        try {
            String token = auth.accessToken();
            for (GoogleTransport.Message m : transport.gmailMessages(token, label, Math.max(0, since - 86_400))) {   // 1-day overlap; external_seen de-duplicates
                for (GoogleTransport.Attachment a : m.attachments()) {
                    String ext = m.id() + ":" + a.attachmentId();
                    if (seen("GMAIL", ext)) continue;
                    String mime = DocumentService.mimeFor(a.filename(), a.mime());
                    if (!MIME.contains(mime)) { mark("GMAIL", ext, a.filename(), null); continue; }
                    byte[] bytes = transport.gmailAttachment(token, m.id(), a.attachmentId());
                    if (batch == null) batch = docs.newBatch("GMAIL");
                    Map<String, Object> d = docs.ingest(bytes, a.filename(), mime, "GMAIL", batch);
                    mark("GMAIL", ext, a.filename(), d.get("id")); n++;
                }
            }
            settings.set("google.gmail_last_sync_epoch", String.valueOf(started));
            settings.set("google.gmail_last_sync_result", Instant.now() + " imported " + n);
        } catch (Exception e) { log.warn("Gmail sync failed: {}", e.getMessage()); settings.set("google.gmail_last_sync_result", Instant.now() + " FAILED: " + e.getMessage()); throw new IllegalStateException("Gmail sync failed: " + e.getMessage(), e); }
        return n;
    }

    public synchronized int syncDrive() {
        if (!auth.connected()) return 0;
        String folder = settings.get("google.drive_folder_id", "");
        if (folder.isBlank()) return 0;
        int n = 0; Long batch = null;
        try {
            String token = auth.accessToken();
            for (GoogleTransport.DriveFile f : transport.driveFiles(token, folder)) {
                String ext = f.id() + ":" + f.md5();
                if (seen("DRIVE", ext)) continue;
                String mime = DocumentService.mimeFor(f.name(), f.mime());
                if (!MIME.contains(mime)) { mark("DRIVE", ext, f.name(), null); continue; }
                byte[] bytes = transport.driveDownload(token, f.id());
                if (batch == null) batch = docs.newBatch("DRIVE");
                Map<String, Object> d = docs.ingest(bytes, f.name(), mime, "DRIVE", batch);
                mark("DRIVE", ext, f.name(), d.get("id")); n++;
            }
            settings.set("google.drive_last_sync_result", Instant.now() + " imported " + n);
        } catch (Exception e) { log.warn("Drive sync failed: {}", e.getMessage()); settings.set("google.drive_last_sync_result", Instant.now() + " FAILED: " + e.getMessage()); throw new IllegalStateException("Drive sync failed: " + e.getMessage(), e); }
        return n;
    }

    @Scheduled(fixedDelayString = "${account0.google.sync-ms:600000}", initialDelayString = "${account0.google.sync-ms:600000}")
    void scheduled() {
        if (!auth.connected()) return;
        try { syncGmail(); } catch (Exception ignored) {}
        try { syncDrive(); } catch (Exception ignored) {}
    }

    Map<String, Object> status(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean gmail = name.equals("Gmail");
        m.put("name", gmail ? "Gmail" : "Google Drive");
        m.put("configured", auth.configured()); m.put("connected", auth.connected()); m.put("account", auth.email());
        m.put("stub", !auth.connected());       // UI shows the dashed STUB box until a real account is connected
        m.put("untestedAgainstLiveGoogle", true);
        m.put("target", gmail ? "label: " + settings.get("google.gmail_label", "Account0/Invoices") : "folder id: " + (settings.get("google.drive_folder_id", "").isBlank() ? "(not set)" : settings.get("google.drive_folder_id", "")));
        m.put("lastSync", settings.get(gmail ? "google.gmail_last_sync_result" : "google.drive_last_sync_result", "never"));
        m.put("message", !auth.configured() ? "STUB: not configured. Create a Google Cloud OAuth client (type: Web) with redirect URI " + props.getRedirectUri() + ", then set ACCOUNT0_GOOGLE_CLIENT_ID and ACCOUNT0_GOOGLE_CLIENT_SECRET in .env and restart."
                : !auth.connected() ? "STUB: credentials present but no account connected. Click 'Connect Google' to grant read-only access."
                : "Connected as " + auth.email() + " (read-only). Built and tested against a mock only - not yet verified against live Google.");
        return m;
    }

    @Component public class Gmail implements ExternalSource {
        public String name() { return "Gmail"; } public boolean connected() { return auth.connected(); } public int sync() { return syncGmail(); } public Map<String, Object> status() { return GoogleSources.this.status("Gmail"); }
    }
    @Component public class Drive implements ExternalSource {
        public String name() { return "Google Drive"; } public boolean connected() { return auth.connected(); } public int sync() { return syncDrive(); } public Map<String, Object> status() { return GoogleSources.this.status("Drive"); }
    }
}
