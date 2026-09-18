package com.parking.model;

import java.time.Instant;
import java.util.UUID;

/** Parking ticket issued on entry, closed (and paid) on exit. */
public class Ticket {
    private final String id;
    private final Vehicle vehicle;
    private final String spotId;
    private final Instant entryTime;
    private Instant exitTime;
    private double fee;
    private PaymentMethod paymentMethod;
    private String paymentAccount;
    private String paymentRef;
    private double appliedRate = -1;

    public Ticket(Vehicle vehicle, String spotId) {
        this.id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.vehicle = vehicle;
        this.spotId = spotId;
        this.entryTime = Instant.now();
    }

    public Ticket(String id, Vehicle vehicle, String spotId, Instant entry, Instant exit, double fee,
                  PaymentMethod method, String account, String ref) {
        this.id = id; this.vehicle = vehicle; this.spotId = spotId;
        this.entryTime = entry; this.exitTime = exit; this.fee = fee;
        this.paymentMethod = method; this.paymentAccount = account; this.paymentRef = ref;
    }

    public String getId() { return id; }
    public Vehicle getVehicle() { return vehicle; }
    public String getSpotId() { return spotId; }
    public Instant getEntryTime() { return entryTime; }
    public Instant getExitTime() { return exitTime; }
    public double getFee() { return fee; }
    public boolean isPaid() { return exitTime != null; }
    public boolean isActive() { return exitTime == null; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public String getPaymentAccount() { return paymentAccount; }
    public String getPaymentRef() { return paymentRef; }
    public double getAppliedRate() { return appliedRate; }
    public void setAppliedRate(double r) { this.appliedRate = r; }

    public void close(Instant exit, double fee, double rate, PaymentMethod method, String account) {
        this.appliedRate = rate;
        if (!isActive()) throw new IllegalStateException("Ticket already closed");
        this.exitTime = exit;
        this.fee = fee;
        this.paymentMethod = method;
        this.paymentAccount = account == null ? "" : account;
        this.paymentRef = "TXN-" + Long.toHexString(exit.toEpochMilli()).toUpperCase() + "-" + id.substring(0, 4);
    }
}
