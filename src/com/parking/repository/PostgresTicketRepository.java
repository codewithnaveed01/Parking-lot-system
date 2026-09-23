package com.parking.repository;

import com.parking.model.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Prepared-statement PostgreSQL ticket repository (existing rows are migrated in place). */
public class PostgresTicketRepository implements TicketRepository {
    private final Database db;
    public PostgresTicketRepository(Database db) { this.db = db; }

    private static void instant(PreparedStatement ps, int i, Instant value) throws SQLException {
        if (value == null) ps.setNull(i, Types.TIMESTAMP_WITH_TIMEZONE);
        else ps.setTimestamp(i, Timestamp.from(value));
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp t = rs.getTimestamp(name); return t == null ? null : t.toInstant();
    }

    @Override public void save(Ticket t) {
        String sql = "INSERT INTO tickets (id,vehicle_type,plate,owner,spot_id,entry_time,exit_time,fee,payment_method,payment_account,payment_ref,applied_rate," +
                "ticket_status,channel,created_at,expires_at,pending_ref,pending_fee,pending_at,collected_by,cash_tendered,note,audit_trail)" +
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO UPDATE SET" +
                " spot_id=EXCLUDED.spot_id,entry_time=EXCLUDED.entry_time,exit_time=EXCLUDED.exit_time,fee=EXCLUDED.fee," +
                " payment_method=EXCLUDED.payment_method,payment_account=EXCLUDED.payment_account,payment_ref=EXCLUDED.payment_ref," +
                " applied_rate=EXCLUDED.applied_rate,ticket_status=EXCLUDED.ticket_status,channel=EXCLUDED.channel," +
                " created_at=EXCLUDED.created_at,expires_at=EXCLUDED.expires_at,pending_ref=EXCLUDED.pending_ref," +
                " pending_fee=EXCLUDED.pending_fee,pending_at=EXCLUDED.pending_at,collected_by=EXCLUDED.collected_by," +
                " cash_tendered=EXCLUDED.cash_tendered,note=EXCLUDED.note,audit_trail=EXCLUDED.audit_trail";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, t.getId()); ps.setString(2, t.getVehicle().getType().name());
            ps.setString(3, t.getVehicle().getLicensePlate()); ps.setString(4, t.getVehicle().getOwnerName());
            ps.setString(5, t.getSpotId()); instant(ps, 6, t.getEntryTime()); instant(ps, 7, t.getExitTime());
            ps.setDouble(8, t.getFee()); ps.setString(9, t.getPaymentMethod() == null ? null : t.getPaymentMethod().name());
            ps.setString(10, t.getPaymentAccount()); ps.setString(11, t.getPaymentRef());
            if (t.getAppliedRate() < 0) ps.setNull(12, Types.NUMERIC); else ps.setDouble(12, t.getAppliedRate());
            ps.setString(13, t.getStatus().name()); ps.setString(14, t.getChannel().name());
            instant(ps, 15, t.getCreatedAt()); instant(ps, 16, t.getExpiresAt());
            ps.setString(17, t.getPendingRef()); ps.setDouble(18, t.getPendingFee()); instant(ps, 19, t.getPendingAt());
            ps.setString(20, t.getCollectedBy()); ps.setDouble(21, t.getCashTendered()); ps.setString(22, t.getNote());
            ps.setString(23, t.getAuditTrail());
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("DB ticket save failed", e); }
    }

    @Override public List<Ticket> findAll() {
        List<Ticket> out = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT * FROM tickets ORDER BY created_at NULLS LAST, entry_time"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Vehicle v = Vehicle.create(VehicleType.valueOf(rs.getString("vehicle_type")), rs.getString("plate"), rs.getString("owner"));
                String method = rs.getString("payment_method");
                Ticket t = new Ticket(rs.getString("id"), v, rs.getString("spot_id"), instant(rs, "entry_time"), instant(rs, "exit_time"),
                        rs.getDouble("fee"), method == null ? null : PaymentMethod.valueOf(method),
                        rs.getString("payment_account"), rs.getString("payment_ref"));
                double rate = rs.getDouble("applied_rate"); if (!rs.wasNull()) t.setAppliedRate(rate);
                t.restore(rs.getString("ticket_status"), rs.getString("channel"), instant(rs, "created_at"), instant(rs, "expires_at"),
                        rs.getString("pending_ref"), rs.getDouble("pending_fee"), instant(rs, "pending_at"),
                        rs.getString("collected_by"), rs.getDouble("cash_tendered"), rs.getString("note"), rs.getString("audit_trail"));
                out.add(t);
            }
        } catch (SQLException e) { throw new IllegalStateException("DB ticket read failed", e); }
        return out;
    }
}
