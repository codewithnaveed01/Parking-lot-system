package com.parking.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** HMAC signing for receipts, SHA-256 password hashing, secure tokens. JDK only. */
public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private final byte[] secret;

    /** Use an explicit, already persisted signing key (e.g. stored in the database). */
    public Crypto(byte[] secret) {
        if (secret == null || secret.length < 16) throw new IllegalArgumentException("Receipt signing key must be at least 16 bytes");
        this.secret = secret.clone();
    }

    /** Creates a new random key encoded as URL-safe Base64 text. */
    public static String newSecret() {
        byte[] k = new byte[32]; RNG.nextBytes(k);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    public Crypto(Path keyFile) {
        byte[] k;
        String env = System.getenv("RECEIPT_SECRET");
        if (env != null && env.length() >= 16) { this.secret = env.getBytes(StandardCharsets.UTF_8); return; }
        try {
            if (Files.exists(keyFile)) k = Files.readAllBytes(keyFile);
            else {
                k = new byte[32]; RNG.nextBytes(k);
                if (keyFile.getParent() != null) Files.createDirectories(keyFile.getParent());
                Files.write(keyFile, k);
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot persist receipt signing key; refusing to issue unverifiable receipts", e); }
        this.secret = k;
    }

    public String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public boolean verify(String data, String sig) {
        if (sig == null) return false;
        return MessageDigest.isEqual(hmac(data).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String token() {
        byte[] b = new byte[32]; RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
