package com.account0.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Raw-HTTP Google client (OAuth token endpoint, Gmail v1, Drive v3). READ-ONLY scopes only.
 * NOT yet exercised against live Google (no credentials were available when this was built) - verified against {@link FakeShape} expectations in tests only.
 */
@Component
public class GoogleHttpTransport implements GoogleTransport {
    private final GoogleProperties props; private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public GoogleHttpTransport(GoogleProperties props, ObjectMapper om) { this.props = props; this.om = om; }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String form(Map<String, String> m) { StringJoiner j = new StringJoiner("&"); m.forEach((k, v) -> j.add(enc(k) + "=" + enc(v))); return j.toString(); }

    private JsonNode postForm(String url, Map<String, String> body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("content-type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Google token endpoint returned " + r.statusCode());
        return om.readTree(r.body());
    }
    private JsonNode getJson(String url, String token) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Google API " + url.substring(0, Math.min(60, url.length())) + " returned " + r.statusCode());
        return om.readTree(r.body());
    }
    private byte[] getBytes(String url, String token) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Google download returned " + r.statusCode());
        return r.body();
    }
    private Tokens tokens(JsonNode j, String keepRefresh) {
        return new Tokens(j.path("access_token").asText(), j.has("refresh_token") ? j.get("refresh_token").asText() : keepRefresh, j.path("expires_in").asLong(3600), j.path("scope").asText(""));
    }

    public Tokens exchangeCode(String code, String verifier, String redirectUri) throws Exception {
        return tokens(postForm("https://oauth2.googleapis.com/token", Map.of("grant_type", "authorization_code", "code", code, "client_id", props.getClientId(), "client_secret", props.getClientSecret(), "redirect_uri", redirectUri, "code_verifier", verifier)), null);
    }
    public Tokens refresh(String refreshToken) throws Exception {
        return tokens(postForm("https://oauth2.googleapis.com/token", Map.of("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id", props.getClientId(), "client_secret", props.getClientSecret())), refreshToken);
    }
    public String accountEmail(String t) throws Exception { return getJson("https://gmail.googleapis.com/gmail/v1/users/me/profile", t).path("emailAddress").asText(null); }

    public List<Message> gmailMessages(String t, String label, long after) throws Exception {
        String q = "label:\"" + label + "\" has:attachment" + (after > 0 ? " after:" + after : "");
        JsonNode list = getJson("https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=50&q=" + enc(q), t);
        List<Message> out = new ArrayList<>();
        for (JsonNode m : list.path("messages")) {
            JsonNode full = getJson("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + m.path("id").asText() + "?format=full", t);
            String subject = "", from = "";
            for (JsonNode h : full.path("payload").path("headers")) { if ("Subject".equalsIgnoreCase(h.path("name").asText())) subject = h.path("value").asText(); if ("From".equalsIgnoreCase(h.path("name").asText())) from = h.path("value").asText(); }
            List<Attachment> atts = new ArrayList<>();
            collect(full.path("payload"), atts);
            out.add(new Message(m.path("id").asText(), subject, from, atts));
        }
        return out;
    }
    private void collect(JsonNode part, List<Attachment> out) {
        String fn = part.path("filename").asText("");
        if (!fn.isBlank() && part.path("body").has("attachmentId")) out.add(new Attachment(part.path("body").path("attachmentId").asText(), fn, part.path("mimeType").asText("")));
        for (JsonNode p : part.path("parts")) collect(p, out);
    }
    public byte[] gmailAttachment(String t, String messageId, String attId) throws Exception {
        return Base64.getUrlDecoder().decode(getJson("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + messageId + "/attachments/" + attId, t).path("data").asText());
    }
    public List<DriveFile> driveFiles(String t, String folderId) throws Exception {
        String q = "'" + folderId.replace("'", "") + "' in parents and trashed=false";
        JsonNode j = getJson("https://www.googleapis.com/drive/v3/files?pageSize=100&fields=" + enc("files(id,name,mimeType,md5Checksum)") + "&q=" + enc(q), t);
        List<DriveFile> out = new ArrayList<>();
        for (JsonNode f : j.path("files")) out.add(new DriveFile(f.path("id").asText(), f.path("name").asText(), f.path("mimeType").asText(), f.path("md5Checksum").asText("")));
        return out;
    }
    public byte[] driveDownload(String t, String id) throws Exception { return getBytes("https://www.googleapis.com/drive/v3/files/" + enc(id) + "?alt=media", t); }
    public void revoke(String token) {
        try { http.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/revoke")).header("content-type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token", token)))).build(), HttpResponse.BodyHandlers.discarding()); } catch (Exception ignored) {}
    }
}
