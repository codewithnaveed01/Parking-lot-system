package com.parking.service;

import com.parking.util.Crypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Admin authentication: salted SHA-256 password, bearer session tokens, brute-force throttling. */
public class AdminAuthService {
    private static final String SALT = "five-star-parking::";
    private static final long SESSION_SECONDS = 8 * 3600;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_SECONDS = 60;

    private final String username;
    private final String passwordHash;
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private int failedAttempts = 0;
    private Instant lockedUntil = Instant.EPOCH;

    public AdminAuthService(String username, String password) {
        this.username = username;
        this.passwordHash = Crypto.sha256(SALT + password);
    }

    public synchronized String login(String user, String pass) {
        Instant now = Instant.now();
        if (now.isBefore(lockedUntil))
            throw new ParkingException("Too many attempts. Try again in " + (lockedUntil.getEpochSecond() - now.getEpochSecond()) + "s", 429);
        boolean userOk = user != null && MessageDigest.isEqual(user.getBytes(StandardCharsets.UTF_8), username.getBytes(StandardCharsets.UTF_8));
        boolean passOk = pass != null && MessageDigest.isEqual(Crypto.sha256(SALT + pass).getBytes(StandardCharsets.UTF_8), passwordHash.getBytes(StandardCharsets.UTF_8));
        if (!(userOk && passOk)) {
            if (++failedAttempts >= MAX_ATTEMPTS) { failedAttempts = 0; lockedUntil = now.plusSeconds(LOCK_SECONDS); }
            throw new ParkingException("Invalid username or password", 401);
        }
        failedAttempts = 0;
        String token = Crypto.token();
        sessions.put(token, now.plusSeconds(SESSION_SECONDS));
        return token;
    }

    public boolean isValid(String token) {
        if (token == null) return false;
        Instant exp = sessions.get(token);
        if (exp == null) return false;
        if (Instant.now().isAfter(exp)) { sessions.remove(token); return false; }
        return true;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }
}
