package com.account0.documents;

import java.util.Map;

/**
 * A remote place invoices arrive (Gmail label, Drive folder). Implemented in com.account0.google behind Google OAuth (read-only scopes);
 * until credentials are added and an account is connected they report "not connected" (shown as a STUB in the UI) and import nothing.
 */
public interface ExternalSource {
    String name();
    boolean connected();
    /** Pull new attachments into the inbox. Returns count imported. */
    int sync();
    default Map<String, Object> status() {
        return Map.of("name", name(), "connected", connected(), "stub", true,
                "message", "STUB: " + name() + " integration is not built yet (Phase 8). Set up Google OAuth credentials, then this will poll for invoices.");
    }
}
