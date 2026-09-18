package com.parking.repository;

import com.parking.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Simple, dependency-free persistence: one pipe-delimited line per ticket. Thread-safe. */
public class FileTicketRepository implements TicketRepository {
    private final Path file;
    private final Map<String, Ticket> cache = new LinkedHashMap<>();

    public FileTicketRepository(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                Ticket t = parse(line);
                if (t != null) cache.put(t.getId(), t);
            }
        } catch (IOException e) {
            System.err.println("[repo] failed to load: " + e.getMessage());
        }
    }

    private Ticket parse(String line) {
        try {
            String[] p = line.split("\\|", -1);
            if (p.length < 8) return null;
            Vehicle v = Vehicle.create(VehicleType.valueOf(p[1]), p[2], decode(p[3]));
            Instant entry = Instant.parse(p[5]);
            Instant exit = p[6].isEmpty() ? null : Instant.parse(p[6]);
            PaymentMethod pm = p.length > 8 && !p[8].isEmpty() ? PaymentMethod.valueOf(p[8]) : (exit != null ? PaymentMethod.CASH : null);
            String acct = p.length > 9 ? p[9] : "";
            String ref = p.length > 10 ? p[10] : "";
            Ticket t = new Ticket(p[0], v, p[4], entry, exit, Double.parseDouble(p[7]), pm, acct, ref);
            if (p.length > 11 && !p[11].isEmpty()) t.setAppliedRate(Double.parseDouble(p[11]));
            return t;
        } catch (Exception e) {
            return null; // skip corrupt lines
        }
    }

    private static String encode(String s) { return s.replace("|", " ").replace("\n", " "); }
    private static String decode(String s) { return s; }

    private String serialize(Ticket t) {
        return String.join("|",
                t.getId(), t.getVehicle().getType().name(), t.getVehicle().getLicensePlate(),
                encode(t.getVehicle().getOwnerName()), t.getSpotId(), t.getEntryTime().toString(),
                t.getExitTime() == null ? "" : t.getExitTime().toString(),
                String.valueOf(t.getFee()),
                t.getPaymentMethod() == null ? "" : t.getPaymentMethod().name(),
                t.getPaymentAccount() == null ? "" : encode(t.getPaymentAccount()),
                t.getPaymentRef() == null ? "" : t.getPaymentRef(),
                t.getAppliedRate() < 0 ? "" : String.valueOf(t.getAppliedRate()));
    }

    @Override
    public synchronized void save(Ticket t) {
        cache.put(t.getId(), t);
        flush();
    }

    private void flush() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Ticket t : cache.values()) { w.write(serialize(t)); w.newLine(); }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[repo] failed to save: " + e.getMessage());
        }
    }

    @Override
    public synchronized List<Ticket> findAll() { return new ArrayList<>(cache.values()); }
}
