package com.account0.google;

import java.util.List;

/** The wire to Google. Real implementation: {@link GoogleHttpTransport}. Tests use an in-memory fake - nothing here has been run against live Google. */
public interface GoogleTransport {
    record Tokens(String accessToken, String refreshToken, long expiresInSeconds, String scope) {}
    record Attachment(String attachmentId, String filename, String mime) {}
    record Message(String id, String subject, String from, List<Attachment> attachments) {}
    record DriveFile(String id, String name, String mime, String md5) {}

    Tokens exchangeCode(String code, String codeVerifier, String redirectUri) throws Exception;
    Tokens refresh(String refreshToken) throws Exception;
    String accountEmail(String accessToken) throws Exception;
    List<Message> gmailMessages(String accessToken, String label, long afterEpochSeconds) throws Exception;
    byte[] gmailAttachment(String accessToken, String messageId, String attachmentId) throws Exception;
    List<DriveFile> driveFiles(String accessToken, String folderId) throws Exception;
    byte[] driveDownload(String accessToken, String fileId) throws Exception;
    void revoke(String token);
}
