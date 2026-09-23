package com.parking.repository;

import com.parking.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Atomic, backwards-compatible local persistence. A failed write never changes the in-memory cache. */
public class FileTicketRepository implements TicketRepository {
    private final Path file;
    private final Map<String, Ticket> cache = new LinkedHashMap<>();

    public FileTicketRepository(Path file) { this.file = file; load(); }

    private void load() {
        if (!Files.exists(file)) return;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line; int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                try {
                    Ticket t = parse(line);
                    cache.put(t.getId(), t);
                } catch (Exception e) { throw new IllegalStateException("Invalid ticket data on line " + lineNo + "; backup the file before repair", e); }
            }
        } catch (IOException e) { throw new IllegalStateException("Could not load tickets", e); }
    }

    private static Instant instant(String s) { return s == null || s.isEmpty() ? null : Instant.parse(s); }
    private static String at(String[] p, int index) { return p.length > index ? p[index] : ""; }
    private static double number(String s) { return s == null || s.isEmpty() ? 0 : Double.parseDouble(s); }
    private static String decode(String s) {
        return s.startsWith("~") ? new String(Base64.getUrlDecoder().decode(s.substring(1)), StandardCharsets.UTF_8) : s;
    }
    private static String encode(String s) {
        return "~" + Base64.getUrlEncoder().withoutPadding().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private Ticket parse(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length < 8) throw new IllegalArgumentException("Missing fields");
        Vehicle vehicle = Vehicle.create(VehicleType.valueOf(p[1]), p[2], decode(p[3]));
        Instant entry = instant(p[5]), exit = instant(p[6]);
        PaymentMethod method = at(p, 8).isEmpty() ? (exit != null && p.length < 13 ? PaymentMethod.CASH : null) : PaymentMethod.valueOf(p[8]);
        Ticket t = new Ticket(p[0], vehicle, p[4], entry, exit, number(p[7]), method, at(p, 9), at(p, 10));
        if (!at(p, 11).isEmpty()) t.setAppliedRate(number(p[11]));
        if (p.length >= 13) t.restore(at(p, 12), at(p, 13), instant(at(p, 14)), instant(at(p, 15)),
                at(p, 16), number(at(p, 17)), instant(at(p, 18)), decode(at(p, 19)), number(at(p, 20)), decode(at(p, 21)), decode(at(p, 22)));
        return t;
    }

    private static String time(Instant instant) { return instant == null ? "" : instant.toString(); }
    private String serialize(Ticket t) {
        return String.join("|", t.getId(), t.getVehicle().getType().name(), t.getVehicle().getLicensePlate(),
                encode(t.getVehicle().getOwnerName()), t.getSpotId(), time(t.getEntryTime()), time(t.getExitTime()),
                String.valueOf(t.getFee()), t.getPaymentMethod() == null ? "" : t.getPaymentMethod().name(),
                t.getPaymentAccount(), t.getPaymentRef(), t.getAppliedRate() < 0 ? "" : String.valueOf(t.getAppliedRate()),
                t.getStatus().name(), t.getChannel().name(), time(t.getCreatedAt()), time(t.getExpiresAt()),
                t.getPendingRef(), String.valueOf(t.getPendingFee()), time(t.getPendingAt()), encode(t.getCollectedBy()),
                String.valueOf(t.getCashTendered()), encode(t.getNote()), encode(t.getAuditTrail()));
    }

    @Override public synchronized void save(Ticket t) {
        Map<String, Ticket> next = new LinkedHashMap<>(cache);
        next.put(t.getId(), t);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Ticket ticket : next.values()) { writer.write(serialize(ticket)); writer.newLine(); }
            }
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
            cache.clear(); cache.putAll(next);
        } catch (IOException e) { throw new IllegalStateException("Ticket could not be saved", e); }
    }

    @Override public synchronized List<Ticket> findAll() { return new ArrayList<>(cache.values()); }
}
