package com.parking.repository;

import com.parking.model.PaymentMethod;
import com.parking.model.Withdrawal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Append-only file store for withdrawals (used when no DATABASE_URL is configured). */
public class FileWithdrawalRepository implements WithdrawalRepository {
    private final Path file;
    private final List<Withdrawal> items = new ArrayList<>();

    public FileWithdrawalRepository(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    String[] p = line.split("\\|", -1);
                    items.add(new Withdrawal(p[0], PaymentMethod.valueOf(p[1]), p[2], Double.parseDouble(p[3]), Instant.parse(p[4]), p[5]));
                } catch (Exception ignored) {}
            }
        } catch (IOException e) { System.err.println("[withdrawals] load failed: " + e.getMessage()); }
    }

    @Override public synchronized void save(Withdrawal w) {
        items.add(w);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            String line = String.join("|", w.getId(), w.getMethod().name(), w.getAccount().replace("|", " "),
                    String.valueOf(w.getAmount()), w.getTime().toString(), w.getReference()) + System.lineSeparator();
            Files.write(file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { System.err.println("[withdrawals] save failed: " + e.getMessage()); }
    }

    @Override public synchronized List<Withdrawal> findAll() { return new ArrayList<>(items); }
}
