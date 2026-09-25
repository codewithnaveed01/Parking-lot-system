package com.parking.repository;

import java.net.URI;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * PostgreSQL gateway with a small connection pool.
 *
 * Accepts DATABASE_URL in JDBC form (jdbc:postgresql://host/db?user=..&password=..)
 * or URI form (postgres:// / postgresql://user:pass@host:5432/db) as provided by Railway,
 * Neon, Supabase and Render. When DATABASE_URL is missing, Railway's PGHOST / PGPORT /
 * PGUSER / PGPASSWORD / PGDATABASE variables are used instead (see {@link #fromEnvironment()}).
 */
public class Database {
    private static final int POOL_SIZE = 5;
    private static final int STARTUP_ATTEMPTS = 10;

    private final String jdbcUrl;
    private final Properties props = new Properties();
    private final BlockingQueue<Connection> idle = new ArrayBlockingQueue<>(POOL_SIZE);

    public Database(String databaseUrl) {
        String url = databaseUrl.trim();
        if (url.startsWith("jdbc:")) {
            jdbcUrl = url;
        } else {
            URI u = URI.create(url);
            String userInfo = u.getRawUserInfo();
            if (userInfo != null) {
                String[] up = userInfo.split(":", 2);
                props.setProperty("user", decode(up[0]));
                if (up.length > 1) props.setProperty("password", decode(up[1]));
            }
            if (u.getHost() == null) throw new IllegalStateException("DATABASE_URL has no host: check the Railway variable reference");
            int port = u.getPort() == -1 ? 5432 : u.getPort();
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/postgres" : u.getRawPath();
            String q = u.getRawQuery() == null ? "sslmode=" + defaultSslMode(u.getHost()) : u.getRawQuery();
            jdbcUrl = "jdbc:postgresql://" + u.getHost() + ":" + port + path + "?" + q;
        }
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout", "30");
        props.setProperty("tcpKeepAlive", "true");
        props.setProperty("ApplicationName", "salim-habib-parking");
        try { Class.forName("org.postgresql.Driver"); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("PostgreSQL driver not on classpath (lib/postgresql-*.jar)"); }
        waitUntilReachable();
        migrate();
    }

    /** Builds a URL from DATABASE_URL, DATABASE_PRIVATE_URL, DATABASE_PUBLIC_URL, or the PG* variables. Returns null when none are set. */
    public static String fromEnvironment() {
        for (String name : new String[]{"DATABASE_URL", "DATABASE_PRIVATE_URL", "DATABASE_PUBLIC_URL"}) {
            String v = System.getenv(name);
            if (v != null && !v.trim().isEmpty() && !v.contains("${{")) return v.trim();
        }
        String host = System.getenv("PGHOST");
        if (host == null || host.trim().isEmpty()) return null;
        String port = orDefault(System.getenv("PGPORT"), "5432");
        String db = orDefault(System.getenv("PGDATABASE"), "postgres");
        String user = orDefault(System.getenv("PGUSER"), "postgres");
        String pass = orDefault(System.getenv("PGPASSWORD"), "");
        return "postgresql://" + encode(user) + ":" + encode(pass) + "@" + host.trim() + ":" + port.trim() + "/" + db.trim();
    }

    /** Railway private networking does not use TLS; public/managed hosts get TLS when offered. */
    private static String defaultSslMode(String host) {
        return host.endsWith(".railway.internal") || host.equals("localhost") || host.equals("127.0.0.1") ? "disable" : "prefer";
    }

    private static String orDefault(String v, String d) { return v == null || v.trim().isEmpty() ? d : v; }
    private static String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }
    private static String decode(String s) {
        try { return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    /** Borrow a pooled connection. Closing the returned connection gives it back to the pool. */
    public Connection open() throws SQLException {
        Connection c;
        while ((c = idle.poll()) != null) {
            try { if (!c.isClosed() && c.isValid(2)) return pooled(c); } catch (SQLException ignored) { }
            quietClose(c);
        }
        return pooled(DriverManager.getConnection(jdbcUrl, props));
    }

    private Connection pooled(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) { release(real); return null; }
                    try { return method.invoke(real, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
    }

    private void release(Connection c) {
        try {
            if (c.isClosed()) return;
            if (!c.getAutoCommit()) { c.rollback(); c.setAutoCommit(true); }
            if (!idle.offer(c)) c.close();
        } catch (SQLException e) { quietClose(c); }
    }

    private static void quietClose(Connection c) { try { c.close(); } catch (SQLException ignored) { } }

    /** Railway may start the app before PostgreSQL accepts connections; retry instead of crashing. */
    private void waitUntilReachable() {
        SQLException last = null;
        for (int attempt = 1; attempt <= STARTUP_ATTEMPTS; attempt++) {
            try (Connection c = open()) { return; }
            catch (SQLException e) {
                last = e;
                System.err.println("[db] connection attempt " + attempt + "/" + STARTUP_ATTEMPTS + " failed: " + e.getMessage());
                try { Thread.sleep(Math.min(10000, 1000L * attempt)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new IllegalStateException("Cannot connect to PostgreSQL: " + (last == null ? "interrupted" : last.getMessage()), last);
    }

    private void migrate() {
        String[] required = {
            "CREATE TABLE IF NOT EXISTS tickets (" +
            " id VARCHAR(16) PRIMARY KEY, vehicle_type VARCHAR(16) NOT NULL, plate VARCHAR(20) NOT NULL, owner VARCHAR(80)," +
            " spot_id VARCHAR(12) NOT NULL, entry_time TIMESTAMPTZ, exit_time TIMESTAMPTZ, fee NUMERIC(12,2) NOT NULL DEFAULT 0," +
            " payment_method VARCHAR(16), payment_account VARCHAR(40), payment_ref VARCHAR(40), applied_rate NUMERIC(12,2))",
            "CREATE TABLE IF NOT EXISTS settings (key VARCHAR(64) PRIMARY KEY, value TEXT NOT NULL)",
            "ALTER TABLE tickets ALTER COLUMN entry_time DROP NOT NULL",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS ticket_status VARCHAR(16)",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS channel VARCHAR(16)",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS pending_ref VARCHAR(40)",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS pending_fee NUMERIC(12,2) DEFAULT 0",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS pending_at TIMESTAMPTZ",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS collected_by VARCHAR(80)",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS cash_tendered NUMERIC(12,2) DEFAULT 0",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS note TEXT",
            "ALTER TABLE tickets ADD COLUMN IF NOT EXISTS audit_trail TEXT",
            // Older rows could hold longer payment values than the original column sizes.
            "ALTER TABLE tickets ALTER COLUMN payment_account TYPE VARCHAR(80)",
            "ALTER TABLE tickets ALTER COLUMN payment_ref TYPE VARCHAR(80)",
            "ALTER TABLE tickets ALTER COLUMN pending_ref TYPE VARCHAR(80)"
        };
        // Indexes are an optimisation/safety net: a legacy duplicate row must not stop the whole site from booting.
        String[] optional = {
            "CREATE INDEX IF NOT EXISTS idx_tickets_exit ON tickets(exit_time)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_orbit_active_plate ON tickets(plate) WHERE exit_time IS NULL",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_orbit_active_spot ON tickets(spot_id) WHERE exit_time IS NULL"
        };
        try (Connection c = open(); Statement st = c.createStatement()) {
            for (String sql : required) st.execute(sql);
            for (String sql : optional) {
                try { st.execute(sql); }
                catch (SQLException e) { System.err.println("[db] skipped index (" + e.getMessage() + ")"); }
            }
            System.out.println("[db] connected & schema ready");
        } catch (SQLException e) {
            throw new IllegalStateException("Database migration failed: " + e.getMessage(), e);
        }
    }
}
