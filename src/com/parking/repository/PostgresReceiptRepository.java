package com.parking.repository;

import java.sql.*;
import java.util.Optional;

/** Receipt screenshots in the payment_receipts table (survives Railway redeploys). */
public class PostgresReceiptRepository implements ReceiptRepository {
    private final Database db;
    public PostgresReceiptRepository(Database db) { this.db = db; }

    @Override public void save(Receipt r) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payment_receipts (ticket_id, content_type, sha256, data) VALUES (?,?,?,?) " +
                "ON CONFLICT (ticket_id) DO UPDATE SET content_type = EXCLUDED.content_type, sha256 = EXCLUDED.sha256, data = EXCLUDED.data, created_at = now()")) {
            ps.setString(1, r.ticketId()); ps.setString(2, r.contentType()); ps.setString(3, r.sha256()); ps.setBytes(4, r.data());
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Receipt save failed: " + e.getMessage(), e); }
    }
    @Override public Optional<Receipt> find(String ticketId) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT content_type, data, sha256 FROM payment_receipts WHERE ticket_id = ?")) {
            ps.setString(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Receipt(ticketId, rs.getString(1), rs.getBytes(2), rs.getString(3))) : Optional.empty();
            }
        } catch (SQLException e) { throw new IllegalStateException("Receipt read failed: " + e.getMessage(), e); }
    }
    @Override public boolean hashUsed(String sha256) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM payment_receipts WHERE sha256 = ?")) {
            ps.setString(1, sha256);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new IllegalStateException("Receipt read failed: " + e.getMessage(), e); }
    }
}
