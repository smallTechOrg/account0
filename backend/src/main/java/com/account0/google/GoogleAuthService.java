package com.account0.google;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class GoogleAuthService {
    public static final String SCOPES = "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/userinfo.email";
    private final JdbcTemplate jdbc; private final GoogleProperties props; private final GoogleTransport transport; private final TokenCrypto crypto; private final AuditService audit;
    public GoogleAuthService(JdbcTemplate jdbc, GoogleProperties props, GoogleTransport transport, TokenCrypto crypto, AuditService audit) { this.jdbc = jdbc; this.props = props; this.transport = transport; this.crypto = crypto; this.audit = audit; }

    static String rnd(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    public boolean configured() { return props.configured(); }
    public boolean connected() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM oauth_token WHERE tenant_id=? AND provider='google' AND refresh_token_enc IS NOT NULL", Integer.class, Tenant.current());
        return n != null && n > 0;
    }

    /** Authorization URL with PKCE (S256) and a single-use state; read-only Gmail + Drive scopes. */
    public String authUrl() {
        if (!configured()) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Google is not configured. Add ACCOUNT0_GOOGLE_CLIENT_ID and ACCOUNT0_GOOGLE_CLIENT_SECRET to .env and restart.");
        String state = rnd(24), verifier = rnd(48);
        jdbc.update("DELETE FROM oauth_state WHERE created_at < strftime('%Y-%m-%dT%H:%M:%SZ','now','-1 hour')");
        jdbc.update("INSERT INTO oauth_state(state, code_verifier) VALUES (?,?)", state, verifier);
        try {
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            return "https://accounts.google.com/o/oauth2/v2/auth?" + GoogleHttpTransport.form(new LinkedHashMap<>(Map.of("client_id", props.getClientId(), "redirect_uri", props.getRedirectUri(), "response_type", "code", "scope", SCOPES,
                    "access_type", "offline", "prompt", "consent", "state", state, "code_challenge", challenge, "code_challenge_method", "S256")));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public void handleCallback(String code, String state, String error) {
        if (error != null) throw ApiException.bad("Google returned an error: " + error);
        List<String> v = state == null ? List.of() : jdbc.queryForList("SELECT code_verifier FROM oauth_state WHERE state=?", String.class, state);
        if (v.isEmpty()) throw ApiException.bad("Unknown or expired OAuth state - start the connection again from the Inbox page.");
        jdbc.update("DELETE FROM oauth_state WHERE state=?", state);           // single use
        if (code == null || code.isBlank()) throw ApiException.bad("No authorization code returned.");
        try {
            GoogleTransport.Tokens t = transport.exchangeCode(code, v.get(0), props.getRedirectUri());
            if (t.refreshToken() == null) throw ApiException.bad("Google did not return a refresh token. Remove Account0 from your Google account's third-party access and connect again.");
            String email = null; try { email = transport.accountEmail(t.accessToken()); } catch (Exception ignored) {}
            store(t, email);
            audit.record("user", "google", "oauth", "connect", "account", null, email, "read-only Gmail + Drive access granted");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Could not complete Google sign-in: " + e.getMessage()); }
    }

    private void store(GoogleTransport.Tokens t, String email) {
        jdbc.update("""
                INSERT INTO oauth_token(tenant_id, provider, account_email, access_token_enc, refresh_token_enc, expires_at, scope) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id, provider) DO UPDATE SET account_email=COALESCE(excluded.account_email, account_email), access_token_enc=excluded.access_token_enc,
                  refresh_token_enc=COALESCE(excluded.refresh_token_enc, refresh_token_enc), expires_at=excluded.expires_at, scope=excluded.scope""",
                Tenant.current(), "google", email, crypto.encrypt(t.accessToken()), crypto.encrypt(t.refreshToken()), Instant.now().plusSeconds(t.expiresInSeconds()).toString(), t.scope());
    }

    /** A valid access token, refreshed when within a minute of expiry. */
    public String accessToken() {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM oauth_token WHERE tenant_id=? AND provider='google'", Tenant.current());
        if (r.isEmpty()) throw ApiException.bad("Google is not connected.");
        Map<String, Object> row = r.get(0);
        if (Instant.parse((String) row.get("expires_at")).isAfter(Instant.now().plusSeconds(60))) return crypto.decrypt((String) row.get("access_token_enc"));
        try {
            GoogleTransport.Tokens t = transport.refresh(crypto.decrypt((String) row.get("refresh_token_enc")));
            store(t, null);
            return t.accessToken();
        } catch (Exception e) { throw ApiException.bad("Google token refresh failed - reconnect Google. (" + e.getMessage() + ")"); }
    }

    public Map<String, Object> disconnect() {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT refresh_token_enc FROM oauth_token WHERE tenant_id=? AND provider='google'", Tenant.current());
        if (!r.isEmpty() && r.get(0).get("refresh_token_enc") != null) { try { transport.revoke(crypto.decrypt((String) r.get(0).get("refresh_token_enc"))); } catch (Exception ignored) {} }
        jdbc.update("DELETE FROM oauth_token WHERE tenant_id=? AND provider='google'", Tenant.current());
        audit.record("user", "google", "oauth", "disconnect", "account", null, null, "tokens deleted and revoked");
        return Map.of("connected", false);
    }

    public String email() {
        List<String> l = jdbc.queryForList("SELECT account_email FROM oauth_token WHERE tenant_id=? AND provider='google'", String.class, Tenant.current());
        return l.isEmpty() ? null : l.get(0);
    }
}
