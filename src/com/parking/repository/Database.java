package com.parking.repository;

import java.net.URI;
import java.sql.*;
import java.util.Properties;

/**
 * JDBC helper + schema migration for PostgreSQL.
 *
 * Accepts DATABASE_URL in either JDBC form (jdbc:postgresql://host/db?user=..&password=..)
 * or URL form (postgres://user:pass@host:5432/db). Works with Railway, Neon, Supabase, Render.
 *
 * Schema (normalized, like a typical management-system database):
 *
 *   vehicle_types  (lookup)   spot_types (lookup)   payment_methods (lookup)
 *   floors ──< parking_spots ──< tickets >── vehicles
 *                                  │
 *                                  └──< payments
 *   withdrawals        settings        audit_log
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

    /** Creates all tables, lookup rows, indexes and reporting views if they do not exist. Safe to run on every start. */
    private void migrate() {
        String[] ddl = {
            // ---------- Lookup tables ----------
            "CREATE TABLE IF NOT EXISTS vehicle_types (" +
            "  code        VARCHAR(16) PRIMARY KEY," +
            "  label       VARCHAR(32) NOT NULL," +
            "  size        SMALLINT    NOT NULL CHECK (size BETWEEN 1 AND 4)," +
            "  default_rate NUMERIC(10,2) NOT NULL)",
            "INSERT INTO vehicle_types (code,label,size,default_rate) VALUES" +
            " ('MOTORCYCLE','Motorcycle',1,50),('CAR','Car',2,100),('VAN','Van',3,150),('TRUCK','Truck',4,250)" +
            " ON CONFLICT (code) DO NOTHING",

            "CREATE TABLE IF NOT EXISTS spot_types (" +
            "  code     VARCHAR(16) PRIMARY KEY," +
            "  label    VARCHAR(32) NOT NULL," +
            "  capacity SMALLINT    NOT NULL CHECK (capacity BETWEEN 1 AND 4))",
            "INSERT INTO spot_types (code,label,capacity) VALUES" +
            " ('COMPACT','Compact',1),('REGULAR','Regular',2),('LARGE','Large',3),('OVERSIZE','Oversize',4)" +
            " ON CONFLICT (code) DO NOTHING",

            "CREATE TABLE IF NOT EXISTS payment_methods (" +
            "  code          VARCHAR(16) PRIMARY KEY," +
            "  label         VARCHAR(32) NOT NULL," +
            "  needs_account BOOLEAN     NOT NULL DEFAULT FALSE)",
            "INSERT INTO payment_methods (code,label,needs_account) VALUES" +
            " ('CASH','Cash',FALSE),('EASYPAISA','EasyPaisa',TRUE),('JAZZCASH','JazzCash',TRUE),('BANK','Bank Transfer',TRUE)" +
            " ON CONFLICT (code) DO NOTHING",

            // ---------- Physical layout ----------
            "CREATE TABLE IF NOT EXISTS floors (" +
            "  level      SMALLINT PRIMARY KEY," +
            "  name       VARCHAR(32) NOT NULL," +
            "  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())",

            "CREATE TABLE IF NOT EXISTS parking_spots (" +
            "  id         VARCHAR(12) PRIMARY KEY," +          // e.g. F1-05
            "  floor_level SMALLINT    NOT NULL REFERENCES floors(level) ON DELETE CASCADE," +
            "  number     SMALLINT    NOT NULL," +
            "  spot_type  VARCHAR(16) NOT NULL REFERENCES spot_types(code)," +
            "  is_blocked BOOLEAN     NOT NULL DEFAULT FALSE," +
            "  UNIQUE (floor_level, number))",

            // ---------- Customers / vehicles ----------
            "CREATE TABLE IF NOT EXISTS vehicles (" +
            "  plate        VARCHAR(20) PRIMARY KEY," +
            "  vehicle_type VARCHAR(16) NOT NULL REFERENCES vehicle_types(code)," +
            "  owner_name   VARCHAR(80)," +
            "  first_seen   TIMESTAMPTZ NOT NULL DEFAULT NOW()," +
            "  last_seen    TIMESTAMPTZ NOT NULL DEFAULT NOW()," +
            "  visit_count  INTEGER     NOT NULL DEFAULT 0)",

            // ---------- Tickets ----------
            "CREATE TABLE IF NOT EXISTS tickets (" +
            "  id           VARCHAR(16) PRIMARY KEY," +
            "  plate        VARCHAR(20) NOT NULL REFERENCES vehicles(plate)," +
            "  vehicle_type VARCHAR(16) NOT NULL REFERENCES vehicle_types(code)," +
            "  owner        VARCHAR(80)," +                       // snapshot at entry time
            "  spot_id      VARCHAR(12) NOT NULL REFERENCES parking_spots(id)," +
            "  entry_time   TIMESTAMPTZ NOT NULL," +
            "  exit_time    TIMESTAMPTZ," +
            "  fee          NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (fee >= 0)," +
            "  applied_rate NUMERIC(10,2)," +
            "  status       VARCHAR(12) GENERATED ALWAYS AS (CASE WHEN exit_time IS NULL THEN 'ACTIVE' ELSE 'CLOSED' END) STORED," +
            "  payment_method  VARCHAR(16) REFERENCES payment_methods(code)," +
            "  payment_account VARCHAR(40)," +
            "  payment_ref     VARCHAR(40)," +
            "  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()," +
            "  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()," +
            "  CHECK (exit_time IS NULL OR exit_time >= entry_time))",
            "CREATE INDEX IF NOT EXISTS idx_tickets_active ON tickets(plate) WHERE exit_time IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_tickets_exit   ON tickets(exit_time DESC)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_entry  ON tickets(entry_time DESC)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_spot   ON tickets(spot_id)",

            // ---------- Payments (one row per successful payment) ----------
            "CREATE TABLE IF NOT EXISTS payments (" +
            "  id         BIGSERIAL PRIMARY KEY," +
            "  ticket_id  VARCHAR(16) NOT NULL REFERENCES tickets(id) ON DELETE CASCADE," +
            "  method     VARCHAR(16) NOT NULL REFERENCES payment_methods(code)," +
            "  account    VARCHAR(40)," +
            "  amount     NUMERIC(12,2) NOT NULL CHECK (amount >= 0)," +
            "  reference  VARCHAR(40) NOT NULL UNIQUE," +
            "  paid_at    TIMESTAMPTZ NOT NULL DEFAULT NOW())",
            "CREATE INDEX IF NOT EXISTS idx_payments_paid_at ON payments(paid_at DESC)",

            // ---------- Withdrawals (admin payouts) ----------
            "CREATE TABLE IF NOT EXISTS withdrawals (" +
            "  id        VARCHAR(16) PRIMARY KEY," +
            "  method    VARCHAR(16) NOT NULL REFERENCES payment_methods(code)," +
            "  account   VARCHAR(40) NOT NULL," +
            "  amount    NUMERIC(12,2) NOT NULL CHECK (amount > 0)," +
            "  time      TIMESTAMPTZ NOT NULL DEFAULT NOW()," +
            "  reference VARCHAR(40) NOT NULL UNIQUE)",

            // ---------- Settings & audit ----------
            "CREATE TABLE IF NOT EXISTS settings (" +
            "  key        VARCHAR(64) PRIMARY KEY," +
            "  value      TEXT NOT NULL," +
            "  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())",

            "CREATE TABLE IF NOT EXISTS audit_log (" +
            "  id         BIGSERIAL PRIMARY KEY," +
            "  actor      VARCHAR(32) NOT NULL," +           // 'user' | 'admin' | 'system'
            "  action     VARCHAR(32) NOT NULL," +           // PARK, EXIT, WITHDRAW, RATE_CHANGE, SPOT_BLOCK ...
            "  entity_id  VARCHAR(40)," +
            "  details    TEXT," +
            "  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())",
            "CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at DESC)",

            // ---------- Reporting views ----------
            "CREATE OR REPLACE VIEW v_daily_revenue AS" +
            " SELECT DATE(paid_at) AS day, method, COUNT(*) AS payments, SUM(amount) AS revenue" +
            " FROM payments GROUP BY DATE(paid_at), method ORDER BY day DESC",
            "CREATE OR REPLACE VIEW v_active_tickets AS" +
            " SELECT t.id, t.plate, v.owner_name, t.vehicle_type, t.spot_id, s.floor_level, t.entry_time," +
            "        EXTRACT(EPOCH FROM (NOW() - t.entry_time))/60 AS minutes_parked" +
            " FROM tickets t JOIN vehicles v ON v.plate = t.plate JOIN parking_spots s ON s.id = t.spot_id" +
            " WHERE t.exit_time IS NULL",
            "CREATE OR REPLACE VIEW v_balance AS" +
            " SELECT (SELECT COALESCE(SUM(amount),0) FROM payments)    AS collected," +
            "        (SELECT COALESCE(SUM(amount),0) FROM withdrawals) AS withdrawn," +
            "        (SELECT COALESCE(SUM(amount),0) FROM payments) - (SELECT COALESCE(SUM(amount),0) FROM withdrawals) AS available"
        };
        try (Connection c = open(); Statement st = c.createStatement()) {
            for (String sql : ddl) st.execute(sql);
            System.out.println("[db] connected & schema ready (11 tables, 3 views)");
        } catch (SQLException e) {
            throw new IllegalStateException("Database migration failed: " + e.getMessage(), e);
        }
    }

    /** Registers floors and spots so tickets can reference them (idempotent). */
    public void syncLayout(java.util.List<com.parking.model.ParkingFloor> floors) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement pf = c.prepareStatement("INSERT INTO floors (level,name) VALUES (?,?) ON CONFLICT (level) DO NOTHING");
                 PreparedStatement ps = c.prepareStatement("INSERT INTO parking_spots (id,floor_level,number,spot_type) VALUES (?,?,?,?) ON CONFLICT (id) DO NOTHING")) {
                for (com.parking.model.ParkingFloor f : floors) {
                    pf.setInt(1, f.getLevel()); pf.setString(2, "Floor " + f.getLevel()); pf.addBatch();
                    for (com.parking.model.ParkingSpot s : f.getSpots()) {
                        ps.setString(1, s.getId()); ps.setInt(2, s.getFloor()); ps.setInt(3, s.getNumber()); ps.setString(4, s.getType().name()); ps.addBatch();
                    }
                }
                pf.executeBatch(); ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) { throw new IllegalStateException("Layout sync failed: " + e.getMessage(), e); }
    }

    public void audit(String actor, String action, String entityId, String details) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log (actor,action,entity_id,details) VALUES (?,?,?,?)")) {
            ps.setString(1, actor); ps.setString(2, action); ps.setString(3, entityId); ps.setString(4, details);
            ps.executeUpdate();
        } catch (SQLException e) { System.err.println("[audit] " + e.getMessage()); }
    }
}
