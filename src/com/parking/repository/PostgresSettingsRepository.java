package com.parking.repository;

import java.sql.*;
import java.util.*;

public class PostgresSettingsRepository implements SettingsRepository {
    private final Database db;
    public PostgresSettingsRepository(Database db) { this.db = db; }

    @Override public Map<String, String> loadAll() {
        Map<String, String> m = new HashMap<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT key, value FROM settings"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) m.put(rs.getString(1), rs.getString(2));
        } catch (SQLException e) { throw new IllegalStateException("DB read failed: " + e.getMessage(), e); }
        return m;
    }

    @Override public void put(String key, String value) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO settings (key, value) VALUES (?,?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("DB save failed: " + e.getMessage(), e); }
    }
}
