package com.parking.repository;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Atomic properties-file settings; failures are surfaced to callers, never silently ignored. */
public class FileSettingsRepository implements SettingsRepository {
    private final Path file;
    public FileSettingsRepository(Path file) { this.file = file; }

    @Override public synchronized Map<String, String> loadAll() {
        Map<String, String> m = new HashMap<>();
        if (!Files.exists(file)) return m;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) { properties.load(in); }
        catch (IOException e) { throw new IllegalStateException("Could not read settings", e); }
        properties.stringPropertyNames().forEach(k -> m.put(k, properties.getProperty(k)));
        return m;
    }

    @Override public synchronized void put(String key, String value) {
        Map<String, String> all = loadAll(); all.put(key, value);
        Properties properties = new Properties(); all.forEach(properties::setProperty);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) { properties.store(out, "Orbit Park settings"); }
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Could not save settings", e); }
    }
}
