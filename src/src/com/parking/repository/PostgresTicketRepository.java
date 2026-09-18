package com.parking.repository;

import com.parking.model.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** PostgreSQL implementation. Uses prepared statements only (SQL-injection safe). */
public class PostgresTicketRepository implements TicketRepository {
    private final Database db;
    public PostgresTicketRepository(Database db) { this.db = db; }

    @Override public void save(Ticket t) {
        String sql = "INSERT INTO tickets (id, vehicle_type, plate, owner, spot_id, entry_time, exit_time, fee, payment_method, payment_account, payment_ref, applied_rate)" +
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)" +
                " ON CONFLICT (id) DO UPDATE SET exit_time = EXCLUDED.exit_time, fee = EXCLUDED.fee, payment_method = EXCLUDED.payment_method," +
                " payment_account = EXCLUDED.payment_account, payment_ref = EXCLUDED.payment_ref, applied_rate = EXCLUDED.applied_rate";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, t.getId());
            ps.setString(2, t.getVehicle().getType().name());
            ps.setString(3, t.getVehicle().getLicensePlate());
            ps.setString(4, t.getVehicle().getOwnerName());
            ps.setString(5, t.getSpotId());
            ps.setTimestamp(6, Timestamp.from(t.getEntryTime()));
            if (t.getExitTime() == null) ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE); else ps.setTimestamp(7, Timestamp.from(t.getExitTime()));
            ps.setBigDecimal(8, java.math.BigDecimal.valueOf(t.getFee()));
            ps.setString(9, t.getPaymentMethod() == null ? null : t.getPaymentMethod().name());
            ps.setString(10, t.getPaymentAccount());
            ps.setString(11, t.getPaymentRef());
            if (t.getAppliedRate() < 0) ps.setNull(12, Types.NUMERIC); else ps.setBigDecimal(12, java.math.BigDecimal.valueOf(t.getAppliedRate()));
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("DB save failed: " + e.getMessage(), e); }
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
                    java.math.BigDecimal ar = rs.getBigDecimal("applied_rate");
                    if (ar != null) t.setAppliedRate(ar.doubleValue());
                    out.add(t);
                } catch (Exception skip) { /* ignore corrupt row */ }
            }
        } catch (SQLException e) { throw new IllegalStateException("DB read failed: " + e.getMessage(), e); }
        return out;
    }
}
