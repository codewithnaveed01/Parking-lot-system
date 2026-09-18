package com.parking.model;

import java.time.Instant;
import java.util.UUID;

/** Admin withdrawal of collected revenue to a payout channel. */
public class Withdrawal {
    private final String id;
    private final PaymentMethod method;
    private final String account;
    private final double amount;
    private final Instant time;
    private final String reference;

    public Withdrawal(PaymentMethod method, String account, double amount) {
        this(UUID.randomUUID().toString().substring(0, 8).toUpperCase(), method, account, amount, Instant.now(),
             "WD-" + Long.toHexString(System.currentTimeMillis()).toUpperCase());
    }

    public Withdrawal(String id, PaymentMethod method, String account, double amount, Instant time, String reference) {
        if (method == PaymentMethod.CASH) throw new IllegalArgumentException("Withdrawal requires EasyPaisa, JazzCash or Bank");
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        this.id = id; this.method = method; this.account = account; this.amount = amount; this.time = time; this.reference = reference;
    }

    public String getId() { return id; }
    public PaymentMethod getMethod() { return method; }
    public String getAccount() { return account; }
    public double getAmount() { return amount; }
    public Instant getTime() { return time; }
    public String getReference() { return reference; }
}
