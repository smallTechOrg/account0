package com.account0.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.stream.Stream;

/** Polls the watched inbox folder; imported files are moved to inbox/imported/. */
@Component
public class FolderWatcher {
    private static final Logger log = LoggerFactory.getLogger(FolderWatcher.class);
    private final DocumentService docs;
    private final Path inbox;
    public FolderWatcher(DocumentService docs, @Value("${account0.inbox-dir}") String inbox) {
        this.docs = docs; this.inbox = Path.of(inbox).toAbsolutePath().normalize();
    }
    public Path inbox() { return inbox; }

    @Scheduled(fixedDelayString = "${account0.inbox-poll-ms:10000}", initialDelay = 3000)
    public int scan() {
        int n = 0;
        try {
            Files.createDirectories(inbox.resolve("imported"));
            Long batch = null;
            try (Stream<Path> s = Files.list(inbox)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".")) continue;
                    if (!DocumentService.ALLOWED.contains(DocumentService.mimeFor(name, null))) continue;
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() < 2000) continue; // still being written
                    if (batch == null) batch = docs.newBatch("FOLDER");
                    docs.ingest(Files.readAllBytes(p), name, null, "FOLDER", batch);
                    Files.move(p, inbox.resolve("imported").resolve(System.currentTimeMillis() + "-" + name), StandardCopyOption.REPLACE_EXISTING);
                    n++;
                }
            }
        } catch (Exception e) { log.warn("inbox scan failed: {}", e.getMessage()); }
        return n;
    }
}
