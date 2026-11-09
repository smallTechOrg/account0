package com.account0.google;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM for OAuth tokens at rest. Key = SHA-256 of ACCOUNT0_SECRET_KEY if set, else a random key kept in data/.secret-key (0600). */
@Component
public class TokenCrypto {
    private final SecretKeySpec key;
    public TokenCrypto(@Value("${account0.data-dir}") String dataDir, @Value("${account0.secret-key:}") String configured) throws Exception {
        byte[] raw;
        if (configured != null && !configured.isBlank()) raw = configured.getBytes();
        else {
            Path f = Path.of(dataDir).resolve(".secret-key"); Files.createDirectories(f.getParent());
            if (!Files.exists(f)) {
                byte[] r = new byte[32]; new SecureRandom().nextBytes(r);
                Files.writeString(f, Base64.getEncoder().encodeToString(r));
                try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) {}
            }
            raw = Files.readString(f).trim().getBytes();
        }
        key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(raw), "AES");
    }
    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes());
            byte[] out = new byte[iv.length + ct.length]; System.arraycopy(iv, 0, out, 0, iv.length); System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String decrypt(String enc) {
        if (enc == null) return null;
        try {
            byte[] in = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12));
        } catch (Exception e) { throw new IllegalStateException("Stored token could not be decrypted (was the secret key changed?). Reconnect Google."); }
    }
}
