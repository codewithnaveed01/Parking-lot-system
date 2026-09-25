package com.parking.repository;

import java.sql.*;
import java.util.*;

/**
 * Key/value settings in PostgreSQL. Rate ("rate.CAR") and maintenance ("blocked.C-01") changes are
 * written in the same transaction to the structured vehicle_types / parking_spots tables.
 */
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
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO settings (key, value) VALUES (?,?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
                    ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
                }
                if (key.startsWith("rate.")) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE vehicle_types SET hourly_rate = ?, updated_at = now() WHERE code = ?")) {
                        ps.setBigDecimal(1, new java.math.BigDecimal(value)); ps.setString(2, key.substring(5)); ps.executeUpdate();
                    }
                } else if (key.startsWith("blocked.")) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE parking_spots SET blocked = ?, updated_at = now() WHERE id = ?")) {
                        ps.setBoolean(1, "true".equals(value)); ps.setString(2, key.substring(8)); ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) { throw new IllegalStateException("DB save failed: " + e.getMessage(), e); }
    }
}
