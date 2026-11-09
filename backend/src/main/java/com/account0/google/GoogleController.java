package com.account0.google;

import com.account0.common.ApiException;
import com.account0.common.SettingsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/google")
public class GoogleController {
    private final GoogleAuthService auth; private final GoogleSources sources; private final SettingsService settings; private final GoogleProperties props;
    public GoogleController(GoogleAuthService auth, GoogleSources sources, SettingsService settings, GoogleProperties props) { this.auth = auth; this.sources = sources; this.settings = settings; this.props = props; }

    @GetMapping("/status")
    public Map<String, Object> status() { return Map.of("configured", auth.configured(), "connected", auth.connected(), "account", Objects.toString(auth.email(), ""), "gmail", sources.status("Gmail"), "drive", sources.status("Drive")); }

    @GetMapping("/auth-url") public Map<String, Object> authUrl() { return Map.of("url", auth.authUrl()); }

    /** Google redirects the browser here. State is single-use; on success we bounce back to the UI. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state, @RequestParam(required = false) String error) {
        auth.handleCallback(code, state, error);
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, URI.create(props.getUiReturnUrl() + "?google=connected").toString()).build();
    }

    @PostMapping("/disconnect") public Map<String, Object> disconnect() { return auth.disconnect(); }

    @PutMapping("/config")
    public Map<String, Object> config(@RequestBody Map<String, String> b) {
        if (b.containsKey("gmail_label")) { String l = b.get("gmail_label").trim(); if (l.isEmpty() || l.contains("\"")) throw ApiException.bad("Invalid Gmail label."); settings.set("google.gmail_label", l); }
        if (b.containsKey("drive_folder_id")) { String f = b.get("drive_folder_id").trim(); if (!f.matches("[A-Za-z0-9_\\-]{0,100}")) throw ApiException.bad("Drive folder id looks wrong (copy the id from the folder URL)."); settings.set("google.drive_folder_id", f); }
        return status();
    }

    @PostMapping("/sync/{which}")
    public Map<String, Object> sync(@PathVariable String which) {
        if (!auth.connected()) throw ApiException.bad("Google is not connected.");
        return switch (which) { case "gmail" -> Map.of("imported", sources.syncGmail()); case "drive" -> Map.of("imported", sources.syncDrive()); default -> throw ApiException.bad("which must be gmail or drive"); };
    }
}
