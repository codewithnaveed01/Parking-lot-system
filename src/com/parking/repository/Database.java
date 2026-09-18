package com.parking.repository;

import java.net.URI;
import java.sql.*;
import java.util.Properties;

/**
 * Tiny JDBC helper. Accepts DATABASE_URL in either JDBC form
 * (jdbc:postgresql://host/db?user=..&password=..) or Heroku/Render/Neon form
 * (postgres://user:pass@host:5432/db). Works with Railway, Neon, Supabase, Render.
 */
public class Database {
    private final String jdbcUrl;
    private final Properties props = new Properties();

    public Database(String databaseUrl) {
        if (databaseUrl.startsWith("jdbc:")) {
            jdbcUrl = databaseUrl;
        } else {
            URI u = URI.create(databaseUrl);
            String userInfo = u.getUserInfo();
            if (userInfo != null) {
                String[] up = userInfo.split(":", 2);
                props.setProperty("user", decode(up[0]));
                if (up.length > 1) props.setProperty("password", decode(up[1]));
            }
            int port = u.getPort() == -1 ? 5432 : u.getPort();
            String q = u.getQuery() == null ? "sslmode=prefer" : u.getQuery();
            jdbcUrl = "jdbc:postgresql://" + u.getHost() + ":" + port + u.getPath() + "?" + q;
        }
        try { Class.forName("org.postgresql.Driver"); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("PostgreSQL driver not on classpath (lib/postgresql-*.jar)"); }
        migrate();
    }

    private static String decode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    public Connection open() throws SQLException { return DriverManager.getConnection(jdbcUrl, props); }

    private void migrate() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS tickets (" +
            " id VARCHAR(16) PRIMARY KEY, vehicle_type VARCHAR(16) NOT NULL, plate VARCHAR(20) NOT NULL, owner VARCHAR(80)," +
            " spot_id VARCHAR(12) NOT NULL, entry_time TIMESTAMPTZ NOT NULL, exit_time TIMESTAMPTZ, fee NUMERIC(12,2) NOT NULL DEFAULT 0," +
            " payment_method VARCHAR(16), payment_account VARCHAR(40), payment_ref VARCHAR(40), applied_rate NUMERIC(12,2))",
            "CREATE INDEX IF NOT EXISTS idx_tickets_plate_active ON tickets(plate) WHERE exit_time IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_tickets_exit ON tickets(exit_time)",
            "CREATE TABLE IF NOT EXISTS withdrawals (" +
            " id VARCHAR(16) PRIMARY KEY, method VARCHAR(16) NOT NULL, account VARCHAR(40) NOT NULL, amount NUMERIC(12,2) NOT NULL," +
            " time TIMESTAMPTZ NOT NULL, reference VARCHAR(40) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS settings (key VARCHAR(64) PRIMARY KEY, value TEXT NOT NULL)"
        };
        try (Connection c = open(); Statement st = c.createStatement()) {
            for (String sql : ddl) st.execute(sql);
            System.out.println("[db] connected & schema ready");
        } catch (SQLException e) {
            throw new IllegalStateException("Database migration failed: " + e.getMessage(), e);
        }
    }
}
