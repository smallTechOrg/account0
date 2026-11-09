package com.account0.documents;

import com.account0.extraction.ExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single worker thread: documents are extracted one at a time (bounded spend, no SQLite write contention). */
@Component
public class ExtractionWorker {
    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "extraction"); t.setDaemon(true); return t; });
    private final ExtractionService extraction;
    private final DocumentService docs;

    public ExtractionWorker(ExtractionService extraction, DocumentService docs) { this.extraction = extraction; this.docs = docs; }

    @EventListener
    public void onQueued(DocumentService.DocumentQueued e) { exec.submit(() -> run(e.documentId())); }

    @EventListener(ApplicationReadyEvent.class)
    public void resume() { docs.pending().forEach(id -> exec.submit(() -> run(id))); }

    private void run(long id) {
        try { extraction.extract(id); }
        catch (Exception ex) { log.error("extraction failed for document {}", id, ex); docs.setStatus(id, "FAILED", ex.getMessage()); }
    }
}
