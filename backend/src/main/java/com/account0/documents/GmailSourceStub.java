package com.account0.documents;

import org.springframework.stereotype.Component;

/** STUB (Phase 8): Gmail label poller. */
@Component
public class GmailSourceStub implements ExternalSource {
    public String name() { return "Gmail"; }
    public boolean connected() { return false; }
    public int sync() { return 0; }
}
