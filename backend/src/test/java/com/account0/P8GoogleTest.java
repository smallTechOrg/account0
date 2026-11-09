package com.account0;

import com.account0.google.GoogleTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestSupport.FakeLlmConfig.class, P8GoogleTest.FakeGoogleConfig.class})
class P8GoogleTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        TestSupport.freshDb(r);
        r.add("account0.google.client-id", () -> "test-client-id");
        r.add("account0.google.client-secret", () -> "test-client-secret");
    }

    /** In-memory Google: what the real HTTP transport would return, nothing else. */
    public static class FakeGoogle implements GoogleTransport {
        public final List<String> calls = new ArrayList<>();
        public List<Message> messages = new ArrayList<>();
        public List<DriveFile> files = new ArrayList<>();
        public String lastVerifier, lastCode;
        public Tokens exchangeCode(String code, String verifier, String redirectUri) { calls.add("exchange:" + code); lastCode = code; lastVerifier = verifier; return new Tokens("ACCESS-TOKEN-1", "REFRESH-TOKEN-1", 3600, "gmail.readonly drive.readonly"); }
        public Tokens refresh(String refreshToken) { calls.add("refresh:" + refreshToken); return new Tokens("ACCESS-TOKEN-2", refreshToken, 3600, "x"); }
        public String accountEmail(String t) { return "founder@example.com"; }
        public List<Message> gmailMessages(String t, String label, long after) { calls.add("gmail:" + label + ":" + t); return messages; }
        public byte[] gmailAttachment(String t, String m, String a) { return ("PDFBYTES-" + m + "-" + a).getBytes(); }
        public List<DriveFile> driveFiles(String t, String folderId) { calls.add("drive:" + folderId); return files; }
        public byte[] driveDownload(String t, String id) { return ("DRIVEBYTES-" + id).getBytes(); }
        public void revoke(String token) { calls.add("revoke:" + token); }
    }
    @TestConfiguration public static class FakeGoogleConfig { @Bean @Primary public FakeGoogle fakeGoogle() { return new FakeGoogle(); } }

    @Autowired TestRestTemplate rest;
    @Autowired FakeGoogle google;
    @Autowired JdbcTemplate jdbc;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void oauthSyncAndDisconnect() throws Exception {
        Map<String, Object> st = rest.getForObject("/api/google/status", Map.class);
        assertThat(st.get("configured")).isEqualTo(true); assertThat(st.get("connected")).isEqualTo(false);
        assertThat(String.valueOf(((Map) st.get("gmail")).get("message"))).startsWith("STUB: credentials present");
        assertThat(rest.postForEntity("/api/google/sync/gmail", null, Map.class).getStatusCode().value()).isEqualTo(400);   // not connected

        String url = (String) rest.getForObject("/api/google/auth-url", Map.class).get("url");
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?").contains("client_id=test-client-id").contains("code_challenge_method=S256").contains("access_type=offline")
                .contains("gmail.readonly").contains("drive.readonly").doesNotContain("gmail.send").doesNotContain("gmail.modify").doesNotContain("auth%2Fdrive+").doesNotContain("client_secret");
        String state = url.replaceAll(".*[?&]state=([^&]+).*", "$1");

        // wrong state is rejected and nothing is exchanged
        assertThat(rest.getForEntity("/api/google/callback?code=abc&state=forged", Map.class).getStatusCode().value()).isEqualTo(400);
        assertThat(google.calls).noneMatch(c -> c.startsWith("exchange"));
        // good callback → 302 back to the UI, tokens stored
        java.net.HttpURLConnection hc = (java.net.HttpURLConnection) new URI(rest.getRootUri() + "/api/google/callback?code=auth-code-1&state=" + state).toURL().openConnection();
        hc.setInstanceFollowRedirects(false);
        assertThat(hc.getResponseCode()).isEqualTo(302); assertThat(hc.getHeaderField("Location")).contains("/inbox?google=connected");
        assertThat(google.lastVerifier).isNotBlank();
        // state is single use
        assertThat(rest.getForEntity("/api/google/callback?code=auth-code-1&state=" + state, Map.class).getStatusCode().value()).isEqualTo(400);
        st = rest.getForObject("/api/google/status", Map.class);
        assertThat(st.get("connected")).isEqualTo(true); assertThat(st.get("account")).isEqualTo("founder@example.com");
        // tokens are encrypted at rest
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM oauth_token");
        assertThat(String.valueOf(row.get("access_token_enc"))).doesNotContain("ACCESS-TOKEN").isNotBlank();
        assertThat(String.valueOf(row.get("refresh_token_enc"))).doesNotContain("REFRESH-TOKEN").isNotBlank();

        // config validation
        assertThat(rest.exchange("/api/google/config", HttpMethod.PUT, new HttpEntity<>(Map.of("gmail_label", "bad\"label")), Map.class).getStatusCode().value()).isEqualTo(400);
        assertThat(rest.exchange("/api/google/config", HttpMethod.PUT, new HttpEntity<>(Map.of("drive_folder_id", "../etc")), Map.class).getStatusCode().value()).isEqualTo(400);
        rest.exchange("/api/google/config", HttpMethod.PUT, new HttpEntity<>(Map.of("gmail_label", "Books/Invoices", "drive_folder_id", "FOLDER_123")), Map.class);

        // Gmail sync: pdf + png imported, txt skipped; second run imports nothing
        google.messages = List.of(
                new GoogleTransport.Message("m1", "Invoice Oct", "vendor@x.com", List.of(new GoogleTransport.Attachment("a1", "inv-1.pdf", "application/pdf"), new GoogleTransport.Attachment("a2", "notes.txt", "text/plain"))),
                new GoogleTransport.Message("m2", "Receipt", "v2@x.com", List.of(new GoogleTransport.Attachment("a3", "scan.png", "image/png"))));
        assertThat(rest.postForObject("/api/google/sync/gmail", null, Map.class).get("imported")).isEqualTo(2);
        assertThat(google.calls).anyMatch(c -> c.startsWith("gmail:Books/Invoices:ACCESS-TOKEN-1"));
        assertThat(rest.postForObject("/api/google/sync/gmail", null, Map.class).get("imported")).isEqualTo(0);
        List<Map<String, Object>> docs = rest.getForObject("/api/documents", List.class);
        assertThat(docs.stream().filter(d -> "GMAIL".equals(d.get("source"))).map(d -> d.get("filename")).toList()).containsExactlyInAnyOrder("inv-1.pdf", "scan.png");

        // Drive sync
        google.files = List.of(new GoogleTransport.DriveFile("f1", "bill.pdf", "application/pdf", "md5a"), new GoogleTransport.DriveFile("f2", "sheet.xlsx", "application/vnd.ms-excel", "md5b"));
        assertThat(rest.postForObject("/api/google/sync/drive", null, Map.class).get("imported")).isEqualTo(1);
        assertThat(google.calls).contains("drive:FOLDER_123");
        assertThat(rest.postForObject("/api/google/sync/drive", null, Map.class).get("imported")).isEqualTo(0);

        // expired access token is refreshed before use
        jdbc.update("UPDATE oauth_token SET expires_at='2000-01-01T00:00:00Z'");
        google.messages = List.of();
        rest.postForObject("/api/google/sync/gmail", null, Map.class);
        assertThat(google.calls).contains("refresh:REFRESH-TOKEN-1");
        assertThat(google.calls).anyMatch(c -> c.equals("gmail:Books/Invoices:ACCESS-TOKEN-2"));

        // nothing leaks through the API
        assertThat(rest.getForObject("/api/google/status", String.class)).doesNotContain("TOKEN");
        assertThat(rest.getForObject("/api/sources", String.class)).contains("Gmail").doesNotContain("TOKEN");

        // disconnect revokes and deletes
        rest.postForObject("/api/google/disconnect", null, Map.class);
        assertThat(google.calls).contains("revoke:REFRESH-TOKEN-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_token", Integer.class)).isEqualTo(0);
        assertThat(rest.getForObject("/api/google/status", Map.class).get("connected")).isEqualTo(false);
    }
}
