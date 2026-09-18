package com.parking.repository;

import com.parking.model.PaymentMethod;
import com.parking.model.Withdrawal;
import java.sql.*;
import java.util.*;

public class PostgresWithdrawalRepository implements WithdrawalRepository {
    private final Database db;
    public PostgresWithdrawalRepository(Database db) { this.db = db; }

    @Override public void save(Withdrawal w) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO withdrawals (id, method, account, amount, time, reference) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, w.getId()); ps.setString(2, w.getMethod().name()); ps.setString(3, w.getAccount());
            ps.setBigDecimal(4, java.math.BigDecimal.valueOf(w.getAmount())); ps.setTimestamp(5, Timestamp.from(w.getTime())); ps.setString(6, w.getReference());
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("DB save failed: " + e.getMessage(), e); }
    }

    @Override public List<Withdrawal> findAll() {
        List<Withdrawal> out = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT * FROM withdrawals ORDER BY time"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Withdrawal(rs.getString("id"), PaymentMethod.valueOf(rs.getString("method")), rs.getString("account"),
                    rs.getBigDecimal("amount").doubleValue(), rs.getTimestamp("time").toInstant(), rs.getString("reference")));
        } catch (SQLException e) { throw new IllegalStateException("DB read failed: " + e.getMessage(), e); }
        return out;
    }

    @Override public double total() {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(amount),0) FROM withdrawals"); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getBigDecimal(1).doubleValue() : 0;
        } catch (SQLException e) { throw new IllegalStateException("DB read failed: " + e.getMessage(), e); }
    }
}
