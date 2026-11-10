package com.account0.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Google OAuth client credentials come from the environment (ACCOUNT0_GOOGLE_CLIENT_ID / _SECRET); without them the integration stays "not configured". */
@ConfigurationProperties(prefix = "account0.google")
public class GoogleProperties {
    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "http://127.0.0.1:8710/api/google/callback";
    private String uiReturnUrl = "http://127.0.0.1:3710/inbox";
    private long syncMs = 600_000;
    public boolean configured() { return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank(); }
    public String getClientId() { return clientId; } public void setClientId(String v) { clientId = v; }
    public String getClientSecret() { return clientSecret; } public void setClientSecret(String v) { clientSecret = v; }
    public String getRedirectUri() { return redirectUri; } public void setRedirectUri(String v) { redirectUri = v; }
    public String getUiReturnUrl() { return uiReturnUrl; } public void setUiReturnUrl(String v) { uiReturnUrl = v; }
    public long getSyncMs() { return syncMs; } public void setSyncMs(long v) { syncMs = v; }
}
