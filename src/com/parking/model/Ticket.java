package com.parking.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** A booking and its entire entry, payment, and exit lifecycle. Instances are copied before persistence. */
public class Ticket {
    public enum Status { RESERVED, PARKED, CLOSED, CANCELLED, EXPIRED }
    public enum Channel { ONLINE, GATE }

    private final String id;
    private final Vehicle vehicle;
    private String spotId;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant entryTime;
    private Instant exitTime;
    private Status status;
    private Channel channel;
    private double fee;
    private double appliedRate = -1;
    private PaymentMethod paymentMethod;
    private String paymentAccount = "";     // masked sender last-four only
    private String paymentRef = "";         // external reference for transfers, internal for cash
    private String pendingRef = "";
    private double pendingFee;
    private Instant pendingAt;
    private String collectedBy = "";
    private double cashTendered;
    private String note = "";
    // Append-only, staff-visible lifecycle events; intentionally not placed on public receipts.
    private String auditTrail = "";

    private void audit(Instant when, String event) {
        auditTrail += when + "  " + event.replaceAll("[\\r\\n\\t]", " ") + "\n";
    }

    public Ticket(Vehicle vehicle, String spotId, Channel channel, double rate) {
        this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        this.vehicle = vehicle;
        this.spotId = spotId;
        this.channel = channel;
        this.createdAt = Instant.now();
        this.appliedRate = rate;
        if (channel == Channel.ONLINE) {
            this.status = Status.RESERVED;
            this.expiresAt = createdAt.plus(Duration.ofMinutes(30));
        } else {
            this.status = Status.PARKED;
            this.entryTime = createdAt;
        }
        audit(createdAt, (channel == Channel.ONLINE ? "ONLINE_RESERVATION" : "GATE_ENTRY") + " bay=" + spotId + " rate=" + rate);
    }

    /** Backwards-compatible constructor for existing file/SQL ticket records. */
    public Ticket(String id, Vehicle vehicle, String spotId, Instant entry, Instant exit, double fee,
                  PaymentMethod method, String account, String ref) {
        this.id = id;
        this.vehicle = vehicle;
        this.spotId = spotId;
        this.createdAt = entry == null ? Instant.now() : entry;
        this.entryTime = entry;
        this.exitTime = exit;
        this.status = exit == null ? Status.PARKED : Status.CLOSED;
        this.channel = Channel.GATE;
        this.fee = fee;
        this.paymentMethod = method;
        this.paymentAccount = account == null ? "" : account;
        this.paymentRef = ref == null ? "" : ref;
    }

    /** Used only by repositories when hydrating optional newer columns. */
    public void restore(String state, String source, Instant created, Instant expiry, String pendingRef,
                        double pendingFee, Instant pendingAt, String collectedBy, double cashTendered, String note, String auditTrail) {
        if (state != null && !state.isEmpty()) status = Status.valueOf(state);
        if (source != null && !source.isEmpty()) channel = Channel.valueOf(source);
        if (created != null) createdAt = created;
        expiresAt = expiry;
        this.pendingRef = pendingRef == null ? "" : pendingRef;
        this.pendingFee = pendingFee;
        this.pendingAt = pendingAt;
        this.collectedBy = collectedBy == null ? "" : collectedBy;
        this.cashTendered = cashTendered;
        this.note = note == null ? "" : note;
        this.auditTrail = auditTrail == null ? "" : auditTrail;
    }

    public Ticket copy() {
        Ticket t = new Ticket(id, vehicle, spotId, entryTime, exitTime, fee, paymentMethod, paymentAccount, paymentRef);
        t.status = status; t.channel = channel; t.createdAt = createdAt; t.expiresAt = expiresAt;
        t.pendingRef = pendingRef; t.pendingFee = pendingFee; t.pendingAt = pendingAt;
        t.collectedBy = collectedBy; t.cashTendered = cashTendered; t.note = note; t.appliedRate = appliedRate;
        t.auditTrail = auditTrail;
        return t;
    }

    public String getId() { return id; }
    public Vehicle getVehicle() { return vehicle; }
    public String getSpotId() { return spotId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getEntryTime() { return entryTime; }
    public Instant getExitTime() { return exitTime; }
    public Status getStatus() { return status; }
    public Channel getChannel() { return channel; }
    public double getFee() { return fee; }
    public double getAppliedRate() { return appliedRate; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public String getPaymentAccount() { return paymentAccount; }
    public String getPaymentRef() { return paymentRef; }
    public String getPendingRef() { return pendingRef; }
    public double getPendingFee() { return pendingFee; }
    public Instant getPendingAt() { return pendingAt; }
    public String getCollectedBy() { return collectedBy; }
    public double getCashTendered() { return cashTendered; }
    public String getNote() { return note; }
    public String getAuditTrail() { return auditTrail; }
    public boolean isActive() { return status == Status.RESERVED || status == Status.PARKED; }
    public boolean isPending() { return !pendingRef.isEmpty(); }
    public void setAppliedRate(double rate) { appliedRate = rate; }
    public void setSpotId(String id) { audit(Instant.now(), "BAY_REASSIGNED " + spotId + " -> " + id); spotId = id; } // legacy single-level migration

    public void checkIn(Instant now) {
        if (status != Status.RESERVED || !now.isBefore(expiresAt)) throw new IllegalStateException("Reservation has expired");
        status = Status.PARKED;
        entryTime = now; // Keep the original deadline: previously issued reservation receipts remain verifiable.
        audit(now, "GATE_CHECK_IN bay=" + spotId);
    }

    public void submitTransfer(PaymentMethod method, String ref, double quotedFee, String senderLastFour, Instant now) {
        if (status != Status.PARKED || isPending()) throw new IllegalStateException("Ticket is not available for a new payment");
        paymentMethod = method;
        paymentAccount = senderLastFour.isEmpty() ? "" : "****" + senderLastFour;
        pendingRef = ref;
        pendingFee = quotedFee;
        pendingAt = now;
        audit(now, "TRANSFER_SUBMITTED " + method + ":" + ref + " amount=" + quotedFee + " sender=" + paymentAccount);
        note = "";
    }

    public void rejectTransfer(String reason) {
        if (!isPending()) throw new IllegalStateException("No payment to reject");
        audit(Instant.now(), "TRANSFER_REJECTED " + paymentMethod + ":" + pendingRef + " amount=" + pendingFee + " reason=" + reason);
        pendingRef = ""; pendingFee = 0; pendingAt = null; paymentMethod = null; paymentAccount = "";
        note = reason;
    }

    public void approveTransfer(Instant now, String staff) {
        if (status != Status.PARKED || !isPending()) throw new IllegalStateException("No pending payment");
        fee = pendingFee;
        paymentRef = pendingRef;
        pendingRef = ""; pendingFee = 0;
        // Verification can take time. Preserve the moment the driver requested
        // payment so the waiting period is not added to their parking bill.
        status = Status.CLOSED; exitTime = now; collectedBy = staff;
        note = "Transfer verified against merchant statement";
        audit(now, "TRANSFER_VERIFIED " + paymentMethod + ":" + paymentRef + " amount=" + fee + " by=" + staff + " cutoff=" + pendingAt);
    }

    public void cashExit(Instant now, double due, double tendered, String staff) {
        if (status != Status.PARKED || isPending()) throw new IllegalStateException("Resolve the pending transfer before cash exit");
        status = Status.CLOSED; exitTime = now; fee = due; cashTendered = tendered; collectedBy = staff;
        if (due > 0) { paymentMethod = PaymentMethod.CASH; paymentRef = "CASH-" + id; }
        else { paymentMethod = null; paymentRef = "FREE-" + id; note = "15-minute grace period"; }
        audit(now, (due > 0 ? "CASH_COLLECTED" : "GRACE_EXIT") + " amount=" + due + " tendered=" + tendered + " by=" + staff);
    }

    public void waive(Instant now, String reason, String staff) {
        if (status != Status.PARKED || isPending()) throw new IllegalStateException("Resolve the pending transfer first");
        status = Status.CLOSED; exitTime = now; fee = 0; paymentMethod = null; paymentRef = "WAIVE-" + id;
        collectedBy = staff; note = reason;
        audit(now, "NO_CHARGE_RELEASE by=" + staff + " reason=" + reason);
    }

    public void cancel(Instant now, String reason) {
        if (status != Status.RESERVED) throw new IllegalStateException("Only reservations can be cancelled");
        status = Status.CANCELLED; exitTime = now; note = reason;
        audit(now, "RESERVATION_CANCELLED reason=" + reason);
    }

    public void expire(Instant now) {
        if (status != Status.RESERVED) throw new IllegalStateException("Only reservations can expire");
        status = Status.EXPIRED; exitTime = now; note = "Not checked in within 30 minutes";
        audit(now, "RESERVATION_EXPIRED");
    }
}
