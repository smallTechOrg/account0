package com.account0.documents;

import org.springframework.stereotype.Component;

/** STUB (Phase 8): Google Drive folder poller. */
@Component
public class DriveSourceStub implements ExternalSource {
    public String name() { return "Google Drive"; }
    public boolean connected() { return false; }
    public int sync() { return 0; }
}
