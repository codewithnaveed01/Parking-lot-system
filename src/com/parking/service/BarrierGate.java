package com.parking.service;

import com.parking.model.Ticket;
import com.parking.util.Json;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Opens the exit barrier when a vehicle has paid (or is free) and leaves.
 * Set BARRIER_URL to the barrier controller (e.g. an ESP32 / relay board / ANPR box). It receives
 * POST {"event":"OPEN","plate":..,"spotId":..,"ticketId":..,"at":..} with optional "Authorization: Bearer BARRIER_TOKEN".
 * Without BARRIER_URL the event is only logged and shown on screen.
 */
public class BarrierGate {
    private final String url, token;
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();

    public BarrierGate(String url, String token) {
        this.url = url == null || url.trim().isEmpty() ? null : url.trim();
        this.token = token == null || token.trim().isEmpty() ? null : token.trim();
    }
    public static BarrierGate fromEnvironment() { return new BarrierGate(System.getenv("BARRIER_URL"), System.getenv("BARRIER_TOKEN")); }
    public static BarrierGate disabled() { return new BarrierGate(null, null); }
    public boolean connected() { return url != null; }

    public synchronized void open(Ticket t) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "OPEN"); event.put("plate", t.getVehicle().getLicensePlate());
        event.put("spotId", t.getSpotId()); event.put("ticketId", t.getId()); event.put("at", Instant.now().toString());
        recent.addFirst(event); while (recent.size() > 20) recent.removeLast();
        System.out.println("[barrier] OPEN for " + t.getVehicle().getLicensePlate() + " (bay " + t.getSpotId() + ")");
        if (url == null) return;
        final byte[] body = Json.encode(event).getBytes(StandardCharsets.UTF_8);
        Thread sender = new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(3000); c.setReadTimeout(5000); c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
                try (OutputStream out = c.getOutputStream()) { out.write(body); }
                int code = c.getResponseCode();
                if (code >= 300) System.err.println("[barrier] controller answered HTTP " + code);
                c.disconnect();
            } catch (Exception e) { System.err.println("[barrier] controller unreachable: " + e.getMessage()); }
        }, "barrier-open");
        sender.setDaemon(true); sender.start();
    }
    public synchronized List<Map<String, Object>> recentEvents() { return new ArrayList<>(recent); }
}
