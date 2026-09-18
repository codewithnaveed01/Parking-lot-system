package com.parking.repository;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Properties-file based settings. */
public class FileSettingsRepository implements SettingsRepository {
    private final Path file;
    public FileSettingsRepository(Path file) { this.file = file; }

    @Override public synchronized Map<String, String> loadAll() {
        Map<String, String> m = new HashMap<>();
        if (!Files.exists(file)) return m;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); } catch (IOException e) { return m; }
        p.stringPropertyNames().forEach(k -> m.put(k, p.getProperty(k)));
        return m;
    }

    @Override public synchronized void put(String key, String value) {
        Map<String, String> all = loadAll(); all.put(key, value);
        Properties p = new Properties(); all.forEach(p::setProperty);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) { p.store(out, "Five Star Parking settings"); }
        } catch (IOException e) { System.err.println("[settings] save failed: " + e.getMessage()); }
    }
}
