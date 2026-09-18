package com.parking.repository;

import com.parking.model.Withdrawal;
import java.util.List;

/** Repository abstraction for withdrawals. */
public interface WithdrawalRepository {
    void save(Withdrawal w);
    List<Withdrawal> findAll();
    default double total() { return findAll().stream().mapToDouble(Withdrawal::getAmount).sum(); }
}
