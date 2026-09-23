package com.parking.service;

import com.parking.util.Crypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Independent administrator / gate-operator sessions with brute-force throttling. */
public class AdminAuthService {
    private static final long SESSION_SECONDS = 8 * 3600;
    private final String username;
    private final String passwordHash;
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private int failures = 0;
    private Instant lockedUntil = Instant.EPOCH;

    public AdminAuthService(String username, String password) {
        this.username = username;
        // Salt is unique to each service account, but passwords must still be strong and configured in production.
        this.passwordHash = Crypto.sha256("orbit-park::" + username + "::" + password);
    }

    public synchronized String login(String user, String pass) {
        Instant now = Instant.now();
        if (now.isBefore(lockedUntil)) throw new ParkingException("Too many attempts. Try again in a minute", 429);
        boolean nameOk = user != null && MessageDigest.isEqual(user.getBytes(StandardCharsets.UTF_8), username.getBytes(StandardCharsets.UTF_8));
        boolean passOk = pass != null && MessageDigest.isEqual(Crypto.sha256("orbit-park::" + username + "::" + pass).getBytes(StandardCharsets.UTF_8), passwordHash.getBytes(StandardCharsets.UTF_8));
        if (!(nameOk && passOk)) {
            if (++failures >= 5) { failures = 0; lockedUntil = now.plusSeconds(60); }
            throw new ParkingException("Invalid username or password", 401);
        }
        failures = 0;
        String token = Crypto.token(); sessions.put(token, now.plusSeconds(SESSION_SECONDS));
        return token;
    }

    public boolean isValid(String token) {
        if (token == null) return false;
        Instant expiration = sessions.get(token);
        if (expiration == null) return false;
        if (!Instant.now().isBefore(expiration)) { sessions.remove(token); return false; }
        return true;
    }
    public String getUsername() { return username; }
    public void logout(String token) { if (token != null) sessions.remove(token); }
}
