package com.parking.service;

import com.parking.model.*;
import com.parking.repository.*;
import com.parking.util.Crypto;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Single-level Salim Habib Parking. All capacity-changing operations share this lock. */
public class ParkingLotService {
    private final String name;
    private final Map<String, ParkingSpot> spots = new LinkedHashMap<>();
    private final Map<String, Ticket> activeByPlate = new HashMap<>();
    private final Set<String> blockedSpots = new HashSet<>();
    private final TicketRepository repo;
    private final SettingsRepository settings;
    private final RateTable rates;
    private final HourlyPricing pricing;
    private final Crypto crypto;
    private final PaymentConfig paymentConfig;

    public ParkingLotService(String name, TicketRepository repo, SettingsRepository settings,
                             RateTable rates, HourlyPricing pricing, Crypto crypto, PaymentConfig paymentConfig) {
        this.name = name; this.repo = repo; this.settings = settings;
        this.rates = rates; this.pricing = pricing; this.crypto = crypto; this.paymentConfig = paymentConfig;
        for (VehicleType vt : VehicleType.values()) {
            for (int n = 1; n <= vt.getCapacity(); n++) {
                ParkingSpot spot = new ParkingSpot(vt, n);
                spots.put(spot.getId(), spot);
            }
        }
        settings.loadAll().forEach((k, v) -> {
            if (k.startsWith("blocked.") && "true".equals(v) && spots.containsKey(k.substring(8))) blockedSpots.add(k.substring(8));
        });
        restore();
    }

    private void restore() {
        List<Ticket> legacy = new ArrayList<>();
        for (Ticket t : repo.findAll()) {
            if (!t.isActive()) continue;
            if (t.getStatus() == Ticket.Status.RESERVED && !Instant.now().isBefore(t.getExpiresAt())) {
                Ticket expired = t.copy(); expired.expire(Instant.now()); repo.save(expired); continue;
            }
            ParkingSpot spot = spots.get(t.getSpotId());
            // First protect all valid assignments, irrespective of file row order.
            // Only then allocate legacy floor tickets from remaining zone capacity.
            if (spot == null || spot.getZone() != t.getVehicle().getType()) legacy.add(t);
            else restoreSpot(t, spot);
        }
        for (Ticket t : legacy) {
            String legacyId = t.getId();
            if (activeByPlate.containsKey(t.getVehicle().getLicensePlate()))
                throw new IllegalStateException("Duplicate active plate in storage: " + t.getVehicle().getLicensePlate());
            ParkingSpot spot = bestSpot(t.getVehicle()).orElseThrow(() ->
                    new IllegalStateException("No free bay for legacy ticket " + legacyId));
            Ticket moved = t.copy(); moved.setSpotId(spot.getId()); repo.save(moved);
            restoreSpot(moved, spot);
        }
    }

    private void restoreSpot(Ticket t, ParkingSpot spot) {
        String plate = t.getVehicle().getLicensePlate();
        if (activeByPlate.containsKey(plate)) throw new IllegalStateException("Duplicate active plate in storage: " + plate);
        if (!spot.canPark(t.getVehicle())) throw new IllegalStateException("Duplicate active spot in storage: " + spot.getId());
        if (blockedSpots.remove(spot.getId())) settings.put("blocked." + spot.getId(), "false");
        spot.park(t.getVehicle()); activeByPlate.put(plate, t);
    }

    private void expireReservations() {
        Instant now = Instant.now();
        for (Ticket t : new ArrayList<>(activeByPlate.values())) {
            if (t.getStatus() == Ticket.Status.RESERVED && !now.isBefore(t.getExpiresAt())) {
                Ticket updated = t.copy(); updated.expire(now); repo.save(updated);
                activeByPlate.remove(t.getVehicle().getLicensePlate()); spots.get(t.getSpotId()).release();
            }
        }
    }

    private Optional<ParkingSpot> bestSpot(Vehicle vehicle) {
        return spots.values().stream().filter(s -> !blockedSpots.contains(s.getId()) && s.canPark(vehicle)).findFirst();
    }

    public synchronized Ticket reserve(VehicleType type, String plate, String owner) { return allocate(type, plate, owner, Ticket.Channel.ONLINE); }
    public synchronized Ticket parkVehicle(VehicleType type, String plate, String owner) { return allocate(type, plate, owner, Ticket.Channel.GATE); }
    /** Online "Park now": the bay is assigned and the meter starts immediately, no staff needed. */
    public synchronized Ticket parkNow(VehicleType type, String plate, String owner) { return allocate(type, plate, owner, Ticket.Channel.SELF); }

    private Ticket allocate(VehicleType type, String plate, String owner, Ticket.Channel channel) {
        expireReservations();
        Vehicle v = Vehicle.create(type, plate, owner);
        if (activeByPlate.containsKey(v.getLicensePlate())) throw new ParkingException("This vehicle already has an active booking", 409);
        ParkingSpot spot = bestSpot(v).orElseThrow(() -> new ParkingException("No free spot in the " + type.getLabel() + " zone", 409));
        Ticket t = new Ticket(v, spot.getId(), channel, rates.getRate(type));
        repo.save(t);
        spot.park(v); activeByPlate.put(v.getLicensePlate(), t);
        return t;
    }

    public synchronized Ticket checkIn(String id, String plate) { return checkIn(id, plate, false); }
    /** Driver confirms arrival without staff (e.g. on their phone at the lot). */
    public synchronized Ticket selfCheckIn(String id, String plate) { return checkIn(id, plate, true); }

    private Ticket checkIn(String id, String plate, boolean selfService) {
        Ticket t = requireActive(id, plate);
        if (t.getStatus() != Ticket.Status.RESERVED) throw new ParkingException("This reservation is already checked in", 409);
        Ticket updated = t.copy(); updated.checkIn(Instant.now(), selfService); repo.save(updated);
        activeByPlate.put(t.getVehicle().getLicensePlate(), updated);
        return updated;
    }

    public synchronized Ticket cancelReservation(String id, String plate, String reason) {
        Ticket t = requireActive(id, plate);
        if (t.getStatus() != Ticket.Status.RESERVED) throw new ParkingException("Only unclaimed reservations can be cancelled", 409);
        Ticket updated = t.copy(); updated.cancel(Instant.now(), reason); finish(t, updated);
        return updated;
    }

    private Ticket requireActive(String id, String plate) {
        expireReservations();
        Ticket t = publicTicket(id, plate);
        if (!t.isActive()) throw new ParkingException("Ticket is no longer active", 409);
        return t;
    }

    public synchronized Ticket publicTicket(String id, String plate) {
        if (id == null || !id.trim().matches("[A-Za-z0-9]{8,16}")) throw new ParkingException("A valid booking code is required");
        Ticket t = findTicket(id).orElseThrow(() -> new ParkingException("Booking not found", 404));
        if (!t.getVehicle().getLicensePlate().equals(Vehicle.normalizePlate(plate))) throw new ParkingException("Booking not found", 404);
        return t;
    }

    /** Gate search only. Public lookups always require both the code and plate. */
    public synchronized Ticket gateLookup(String idOrPlate) {
        expireReservations();
        if (idOrPlate == null || idOrPlate.isBlank()) throw new ParkingException("Enter a booking code or license plate");
        String q = idOrPlate.trim().toUpperCase();
        Ticket t = activeByPlate.get(q);
        if (t == null) t = findTicket(q).orElse(null);
        if (t == null) throw new ParkingException("No ticket found", 404);
        return t;
    }

    public synchronized double currentFee(Ticket t) {
        if (t.getStatus() != Ticket.Status.PARKED) return 0;
        // The meter stops when a transfer is submitted. This also prevents a
        // delayed manager verification from unexpectedly increasing the bill.
        if (t.isPending()) return t.getPendingFee();
        return feeAt(t, Instant.now());
    }

    private double feeAt(Ticket t, Instant when) {
        double rate = t.getAppliedRate() >= 0 ? t.getAppliedRate() : rates.getRate(t.getVehicle().getType());
        return pricing.calculateAtRate(Duration.between(t.getEntryTime(), when), rate);
    }

    public synchronized Ticket cashExit(String idOrPlate, double tendered, String staff) {
        Ticket t = gateLookup(idOrPlate);
        if (t.getStatus() != Ticket.Status.PARKED) throw new ParkingException("Vehicle must check in before exit", 409);
        if (t.isPending()) throw new ParkingException("Reject or verify the pending transfer first", 409);
        double due = currentFee(t);
        if (!Double.isFinite(tendered) || tendered < due || tendered > 1_000_000 || (due == 0 && tendered != 0)
                || Math.rint(tendered * 100) != tendered * 100)
            throw new ParkingException(due == 0 ? "This exit is free: record PKR 0 received" : "Cash received must cover the current fee (maximum PKR 1,000,000)");
        Ticket updated = t.copy(); updated.cashExit(Instant.now(), due, tendered, staff); finish(t, updated);
        return updated;
    }

    /**
     * Unattended exit: free within the grace period, or after the driver has submitted an online transfer
     * (the bay is released at once; the transfer stays pending until the manager reconciles it).
     */
    public synchronized Ticket selfExit(String id, String plate) {
        Ticket t = requireActive(id, plate);
        if (t.getStatus() != Ticket.Status.PARKED) throw new ParkingException("Check in before exit", 409);
        double due = currentFee(t);
        if (!t.isPending() && due > 0)
            throw new ParkingException("Pay " + Math.round(due) + " PKR online first, or pay cash at the gate", 409);
        Ticket updated = t.copy(); updated.selfExit(Instant.now(), due); finish(t, updated);
        return updated;
    }

    /** Plate-only exit: the vehicle currently parked with this number plate. */
    public synchronized Ticket parkedByPlate(String plate) {
        expireReservations();
        String key = plate == null ? "" : plate.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (key.length() < 2) throw new ParkingException("Enter the vehicle number plate");
        Ticket t = activeByPlate.values().stream()
                .filter(x -> x.getVehicle().getLicensePlate().replaceAll("[^A-Z0-9]", "").equals(key)).findFirst().orElse(null);
        if (t == null) throw new ParkingException("No parked vehicle found with this number plate", 404);
        if (t.getStatus() != Ticket.Status.PARKED) throw new ParkingException("This vehicle has a reservation but has not checked in yet", 409);
        return t;
    }

    /** Plate-only exit: free exit, or exit after an online transfer was already submitted. */
    public synchronized Ticket exitByPlate(String plate) {
        Ticket t = parkedByPlate(plate);
        return selfExit(t.getId(), t.getVehicle().getLicensePlate());
    }

    /** Plate-only exit: submit the online payment and leave in one step. */
    public synchronized Ticket payAndExitByPlate(String plate, PaymentMethod method, String reference, String lastFour) {
        Ticket t = parkedByPlate(plate);
        String p = t.getVehicle().getLicensePlate();
        if (!t.isPending() && currentFee(t) > 0) submitTransfer(t.getId(), p, method, reference, lastFour);
        return selfExit(t.getId(), p);
    }

    /** Every transfer still awaiting a manager decision, including self-service exits. */
    public synchronized List<Ticket> getPendingPayments() {
        expireReservations();
        return repo.findAll().stream().filter(Ticket::isPending)
                .sorted(Comparator.comparing(Ticket::getPendingAt, Comparator.nullsLast(Comparator.naturalOrder()))).collect(Collectors.toList());
    }

    public synchronized Ticket submitTransfer(String id, String plate, PaymentMethod method, String reference, String senderLastFour) {
        Ticket t = requireActive(id, plate);
        if (t.getStatus() != Ticket.Status.PARKED) throw new ParkingException("Check in before paying", 409);
        if (t.isPending()) throw new ParkingException("This booking already has a transfer awaiting verification", 409);
        if (method == PaymentMethod.CASH || !paymentConfig.enabled(method)) throw new ParkingException("This digital payment destination is not available", 409);
        String ref = reference == null ? "" : reference.trim().toUpperCase();
        if (!ref.matches("[A-Z0-9_\\-]{6,40}")) throw new ParkingException("Enter the transfer reference (6–40 letters/numbers)");
        String lastFour = senderLastFour == null ? "" : senderLastFour.trim();
        if (!lastFour.isEmpty() && !lastFour.matches("\\d{4}")) throw new ParkingException("Sender account last four must be four digits");
        Instant submittedAt = Instant.now();
        double fee = feeAt(t, submittedAt);
        if (fee <= 0) throw new ParkingException("Nothing to pay yet. Use Exit for a free exit", 409);
        for (Ticket existing : repo.findAll()) {
            boolean stillUsed = method == existing.getPaymentMethod()
                    && (ref.equalsIgnoreCase(existing.getPendingRef()) || ref.equalsIgnoreCase(existing.getPaymentRef()));
            boolean alreadyAttempted = existing.getAuditTrail().contains("TRANSFER_SUBMITTED " + method + ":" + ref + " amount=");
            if (stillUsed || alreadyAttempted)
                throw new ParkingException("This reference has already been submitted. Contact staff if this is your payment", 409);
        }
        Ticket updated = t.copy(); updated.submitTransfer(method, ref, fee, lastFour, submittedAt); repo.save(updated);
        activeByPlate.put(t.getVehicle().getLicensePlate(), updated);
        return updated;
    }

    /** An administrator must check the merchant's actual wallet/bank statement before calling this. */
    public synchronized Ticket approveTransfer(String id, String staff) {
        Ticket t = gateLookup(id);
        boolean leftAlready = t.getStatus() == Ticket.Status.CLOSED;
        if ((t.getStatus() != Ticket.Status.PARKED && !leftAlready) || !t.isPending()) throw new ParkingException("No pending transfer to verify", 409);
        if (t.getPendingAt() == null || Math.abs(feeAt(t, t.getPendingAt()) - t.getPendingFee()) > 0.001)
            throw new ParkingException("The submitted transfer amount does not match the fee at the billing cutoff", 409);
        Ticket updated = t.copy(); updated.approveTransfer(Instant.now(), staff);
        if (leftAlready) repo.save(updated); else finish(t, updated);
        return updated;
    }

    public synchronized Ticket rejectTransfer(String id, String reason) {
        Ticket t = gateLookup(id);
        boolean leftAlready = t.getStatus() == Ticket.Status.CLOSED;
        if ((t.getStatus() != Ticket.Status.PARKED && !leftAlready) || !t.isPending()) throw new ParkingException("No pending transfer", 409);
        String note = reason == null ? "" : reason.trim();
        if (note.length() < 4 || note.length() > 120) throw new ParkingException("Explain the rejection in 4–120 characters");
        Ticket updated = t.copy(); updated.rejectTransfer(note); repo.save(updated);
        if (!leftAlready) activeByPlate.put(t.getVehicle().getLicensePlate(), updated);
        return updated;
    }

    public synchronized Ticket waiveExit(String idOrPlate, String reason, String staff) {
        Ticket t = gateLookup(idOrPlate);
        if (t.getStatus() != Ticket.Status.PARKED || t.isPending()) throw new ParkingException("Resolve check-in / pending transfer first", 409);
        String note = reason == null ? "" : reason.trim();
        if (note.length() < 5 || note.length() > 120) throw new ParkingException("Enter an audit reason (5–120 characters)");
        Ticket updated = t.copy(); updated.waive(Instant.now(), note, staff); finish(t, updated);
        return updated;
    }

    private void finish(Ticket old, Ticket updated) {
        repo.save(updated);
        activeByPlate.remove(old.getVehicle().getLicensePlate());
        spots.get(old.getSpotId()).release();
    }

    public synchronized void setSpotBlocked(String id, boolean blocked) {
        ParkingSpot spot = spots.get(id);
        if (spot == null) throw new ParkingException("Spot not found", 404);
        if (blocked && !spot.isFree()) throw new ParkingException("Cannot block a booked or occupied spot", 409);
        settings.put("blocked." + id, String.valueOf(blocked));
        if (blocked) blockedSpots.add(id); else blockedSpots.remove(id);
    }
    public synchronized boolean isBlocked(String id) { return blockedSpots.contains(id); }
    public synchronized void setRate(VehicleType type, double rate) { rates.setRate(type, rate); }
    public RateTable getRateTable() { return rates; }
    public PaymentConfig getPaymentConfig() { return paymentConfig; }
    public String getName() { return name; }

    public synchronized Collection<ParkingSpot> getSpots() { expireReservations(); return new ArrayList<>(spots.values()); }
    public synchronized List<Ticket> getActiveTickets() {
        expireReservations();
        return activeByPlate.values().stream().sorted(Comparator.comparing(Ticket::getCreatedAt).reversed()).collect(Collectors.toList());
    }
    public synchronized List<Ticket> getHistory() {
        expireReservations();
        return repo.findAll().stream().filter(t -> !t.isActive())
                .sorted(Comparator.comparing(Ticket::getCreatedAt).reversed()).collect(Collectors.toList());
    }
    public synchronized Optional<Ticket> findTicket(String id) {
        if (id == null) return Optional.empty();
        String q = id.trim().toUpperCase();
        return repo.findAll().stream().filter(t -> t.getId().equals(q)).findFirst();
    }
    public synchronized Optional<Ticket> ticketForSpot(String id) {
        expireReservations();
        return activeByPlate.values().stream().filter(t -> t.getSpotId().equals(id)).findFirst();
    }

    public synchronized Map<String, Object> getStats() {
        expireReservations();
        List<Ticket> all = repo.findAll();
        Map<String, Object> zones = new LinkedHashMap<>();
        int free = 0, reserved = 0, occupied = 0;
        for (VehicleType type : VehicleType.values()) {
            int zFree = 0, zReserved = 0, zOccupied = 0, zBlocked = 0;
            for (ParkingSpot s : spots.values()) {
                if (s.getZone() != type) continue;
                if (blockedSpots.contains(s.getId())) zBlocked++;
                else if (s.isFree()) zFree++;
                else {
                    Ticket t = activeByPlate.get(s.getVehicle().getLicensePlate());
                    if (t != null && t.getStatus() == Ticket.Status.RESERVED) zReserved++; else zOccupied++;
                }
            }
            free += zFree; reserved += zReserved; occupied += zOccupied;
            zones.put(type.name(), Map.of("total", type.getCapacity(), "free", zFree, "reserved", zReserved, "occupied", zOccupied, "blocked", zBlocked));
        }
        Map<String, Double> byMethod = new LinkedHashMap<>();
        for (PaymentMethod method : PaymentMethod.values()) byMethod.put(method.name(), 0.0);
        double total = 0, day = 0;
        Instant ago = Instant.now().minus(Duration.ofDays(1));
        for (Ticket t : all) {
            if (t.getStatus() == Ticket.Status.CLOSED && t.getPaymentMethod() != null) {
                total += t.getFee();
                if (t.getExitTime().isAfter(ago)) day += t.getFee();
                byMethod.merge(t.getPaymentMethod().name(), t.getFee(), Double::sum);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("totalSpots", spots.size()); m.put("freeSpots", free);
        m.put("reservedSpots", reserved); m.put("occupiedSpots", occupied); m.put("blockedSpots", blockedSpots.size());
        m.put("occupancyPercent", Math.round((occupied + reserved) * 1000.0 / spots.size()) / 10.0);
        m.put("zones", zones); m.put("totalRevenue", Math.round(total * 100) / 100.0);
        m.put("revenue24h", Math.round(day * 100) / 100.0); m.put("revenueByMethod", byMethod);
        m.put("pendingPayments", all.stream().filter(Ticket::isPending).count());
        m.put("unpaidExits", all.stream().filter(Ticket::isUnpaidAfterExit).count());
        m.put("unpaidAmount", Math.round(all.stream().filter(Ticket::isUnpaidAfterExit).mapToDouble(Ticket::getFee).sum() * 100) / 100.0);
        m.put("selfServiceBookings", all.stream().filter(t -> t.getChannel() != Ticket.Channel.GATE).count());
        m.put("totalTickets", all.size());
        m.put("freeExits", all.stream().filter(t -> t.getStatus() == Ticket.Status.CLOSED && t.getPaymentMethod() == null
                && !t.isPending() && !t.isUnpaidAfterExit()).count());
        return m;
    }

    private static String val(Object o) { return o == null ? "" : String.valueOf(o); }
    /** HMAC payloads are phase-specific: a printed reservation / entry pass stays valid after exit. */
    public String receiptPayload(Ticket t, String phase) {
        String common = String.join("|", "ORBIT-v1", phase, t.getId(), t.getVehicle().getLicensePlate(),
                t.getVehicle().getOwnerName(), t.getVehicle().getType().name(), t.getSpotId(),
                t.getChannel().name(), val(t.getCreatedAt()));
        switch (phase) {
            case "RESERVATION":
                if (t.getChannel() != Ticket.Channel.ONLINE) throw new ParkingException("This is not an online reservation");
                return common + "|" + val(t.getExpiresAt());
            case "ENTRY":
                if (t.getEntryTime() == null) throw new ParkingException("Vehicle has not checked in");
                return common + "|" + val(t.getEntryTime()) + "|" + t.getAppliedRate();
            case "PAYMENT":
                if (t.getStatus() != Ticket.Status.CLOSED) throw new ParkingException("No payment receipt yet", 409);
                return common + "|" + val(t.getEntryTime()) + "|" + val(t.getExitTime()) + "|" + t.getAppliedRate()
                        + "|" + t.getFee() + "|" + val(t.getPaymentMethod()) + "|" + t.getPaymentRef()
                        + "|" + t.getPaymentAccount() + "|" + t.getCollectedBy() + "|" + t.getCashTendered() + "|" + t.getNote()
                        // Old cash/digital receipts without a cutoff retain their original signature.
                        + (t.getPendingAt() == null ? "" : "|" + t.getPendingAt());
            default: throw new ParkingException("Unknown receipt type");
        }
    }
    public String sign(Ticket t, String phase) { return crypto.hmac(receiptPayload(t, phase)); }
    public boolean validSignature(Ticket t, String phase, String signature) {
        return crypto.verify(receiptPayload(t, phase), signature);
    }
}
