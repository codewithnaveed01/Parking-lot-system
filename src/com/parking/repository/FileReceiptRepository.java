package com.parking.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import java.util.stream.Stream;

/** Local-file receipt storage: data/receipts/{ticket}.img plus a {ticket}.meta line "contentType|sha256". */
public class FileReceiptRepository implements ReceiptRepository {
    private final Path dir;
    public FileReceiptRepository(Path dir) {
        this.dir = dir;
        try { Files.createDirectories(dir); } catch (IOException e) { throw new IllegalStateException("Cannot create " + dir, e); }
    }
    private static String safe(String id) { return id.replaceAll("[^A-Za-z0-9]", ""); }

    @Override public synchronized void save(Receipt r) {
        try {
            Files.write(dir.resolve(safe(r.ticketId()) + ".img"), r.data());
            Files.write(dir.resolve(safe(r.ticketId()) + ".meta"), (r.contentType() + "|" + r.sha256()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Receipt save failed", e); }
    }
    @Override public synchronized Optional<Receipt> find(String ticketId) {
        Path img = dir.resolve(safe(ticketId) + ".img"), meta = dir.resolve(safe(ticketId) + ".meta");
        if (!Files.exists(img) || !Files.exists(meta)) return Optional.empty();
        try {
            String[] m = new String(Files.readAllBytes(meta), StandardCharsets.UTF_8).split("\\|", 2);
            return Optional.of(new Receipt(ticketId, m[0], Files.readAllBytes(img), m.length > 1 ? m[1] : ""));
        } catch (IOException e) { return Optional.empty(); }
    }
    @Override public synchronized boolean hashUsed(String sha256) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".meta")).anyMatch(p -> {
                try { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).endsWith("|" + sha256); } catch (IOException e) { return false; }
            });
        } catch (IOException e) { return false; }
    }
}
