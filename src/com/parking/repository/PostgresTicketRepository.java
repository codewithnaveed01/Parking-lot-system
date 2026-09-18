package com.parking.repository;

import com.parking.model.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL implementation. One save() = one transaction touching
 * vehicles (upsert), tickets (upsert) and payments (insert on close).
 * Prepared statements only (SQL-injection safe).
 */
public class PostgresTicketRepository implements TicketRepository {
    private final Database db;
    public PostgresTicketRepository(Database db) { this.db = db; }

    @Override public void save(Ticket t) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                upsertVehicle(c, t);
                boolean isNew = upsertTicket(c, t);
                if (t.isPaid()) insertPayment(c, t);
                c.commit();
                db.audit(t.isPaid() ? "user" : "user", t.isPaid() ? "EXIT" : (isNew ? "PARK" : "UPDATE"), t.getId(),
                        t.getVehicle().getLicensePlate() + " @ " + t.getSpotId() + (t.isPaid() ? " fee=" + t.getFee() + " via " + t.getPaymentMethod() : ""));
            } catch (SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException("DB save failed: " + e.getMessage(), e); }
    }

    private void upsertVehicle(Connection c, Ticket t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO vehicles (plate, vehicle_type, owner_name, first_seen, last_seen, visit_count) VALUES (?,?,?,?,?,1)" +
                " ON CONFLICT (plate) DO UPDATE SET vehicle_type = EXCLUDED.vehicle_type," +
                "   owner_name = COALESCE(NULLIF(EXCLUDED.owner_name,'Unknown'), vehicles.owner_name)," +
                "   last_seen = EXCLUDED.last_seen," +
                "   visit_count = vehicles.visit_count + CASE WHEN EXISTS (SELECT 1 FROM tickets WHERE id = ?) THEN 0 ELSE 1 END")) {
            ps.setString(1, t.getVehicle().getLicensePlate());
            ps.setString(2, t.getVehicle().getType().name());
            ps.setString(3, t.getVehicle().getOwnerName());
            ps.setTimestamp(4, Timestamp.from(t.getEntryTime()));
            ps.setTimestamp(5, Timestamp.from(t.getExitTime() == null ? t.getEntryTime() : t.getExitTime()));
            ps.setString(6, t.getId());
            ps.executeUpdate();
        }
    }

    private boolean upsertTicket(Connection c, Ticket t) throws SQLException {
        boolean exists;
        try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM tickets WHERE id = ?")) { q.setString(1, t.getId()); try (ResultSet rs = q.executeQuery()) { exists = rs.next(); } }
        String sql = "INSERT INTO tickets (id, plate, vehicle_type, owner, spot_id, entry_time, exit_time, fee, applied_rate, payment_method, payment_account, payment_ref)" +
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)" +
                " ON CONFLICT (id) DO UPDATE SET exit_time = EXCLUDED.exit_time, fee = EXCLUDED.fee, applied_rate = EXCLUDED.applied_rate," +
                "   payment_method = EXCLUDED.payment_method, payment_account = EXCLUDED.payment_account, payment_ref = EXCLUDED.payment_ref, updated_at = NOW()";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, t.getId());
            ps.setString(2, t.getVehicle().getLicensePlate());
            ps.setString(3, t.getVehicle().getType().name());
            ps.setString(4, t.getVehicle().getOwnerName());
            ps.setString(5, t.getSpotId());
            ps.setTimestamp(6, Timestamp.from(t.getEntryTime()));
            if (t.getExitTime() == null) ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE); else ps.setTimestamp(7, Timestamp.from(t.getExitTime()));
            ps.setBigDecimal(8, BigDecimal.valueOf(t.getFee()));
            if (t.getAppliedRate() < 0) ps.setNull(9, Types.NUMERIC); else ps.setBigDecimal(9, BigDecimal.valueOf(t.getAppliedRate()));
            ps.setString(10, t.getPaymentMethod() == null ? null : t.getPaymentMethod().name());
            ps.setString(11, t.getPaymentAccount());
            ps.setString(12, t.getPaymentRef());
            ps.executeUpdate();
        }
        return !exists;
    }

    private void insertPayment(Connection c, Ticket t) throws SQLException {
        if (t.getPaymentRef() == null) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payments (ticket_id, method, account, amount, reference, paid_at) VALUES (?,?,?,?,?,?) ON CONFLICT (reference) DO NOTHING")) {
            ps.setString(1, t.getId());
            ps.setString(2, t.getPaymentMethod() == null ? "CASH" : t.getPaymentMethod().name());
            ps.setString(3, t.getPaymentAccount());
            ps.setBigDecimal(4, BigDecimal.valueOf(t.getFee()));
            ps.setString(5, t.getPaymentRef());
            ps.setTimestamp(6, Timestamp.from(t.getExitTime()));
            ps.executeUpdate();
        }
    }

    @Override public List<Ticket> findAll() {
        List<Ticket> out = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT * FROM tickets ORDER BY entry_time"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    Vehicle v = Vehicle.create(VehicleType.valueOf(rs.getString("vehicle_type")), rs.getString("plate"), rs.getString("owner"));
                    Timestamp ex = rs.getTimestamp("exit_time");
                    String pm = rs.getString("payment_method");
                    Ticket t = new Ticket(rs.getString("id"), v, rs.getString("spot_id"), rs.getTimestamp("entry_time").toInstant(),
                            ex == null ? null : ex.toInstant(), rs.getBigDecimal("fee").doubleValue(),
                            pm == null ? null : PaymentMethod.valueOf(pm), rs.getString("payment_account"), rs.getString("payment_ref"));
                    BigDecimal ar = rs.getBigDecimal("applied_rate");
                    if (ar != null) t.setAppliedRate(ar.doubleValue());
                    out.add(t);
                } catch (Exception skip) { /* ignore corrupt row */ }
            }
        } catch (SQLException e) { throw new IllegalStateException("DB read failed: " + e.getMessage(), e); }
        return out;
    }
}
