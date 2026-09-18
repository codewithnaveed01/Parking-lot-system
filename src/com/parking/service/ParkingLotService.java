package com.parking.service;

import com.parking.model.*;
import com.parking.repository.TicketRepository;
import com.parking.repository.WithdrawalRepository;
import com.parking.util.Crypto;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core business logic (Facade). All mutating operations are synchronized to prevent races.
 */
public class ParkingLotService {
    private final String name;
    private final List<ParkingFloor> floors = new ArrayList<>();
    private final Map<String, ParkingSpot> spotIndex = new HashMap<>();
    private final Map<String, Ticket> activeByPlate = new HashMap<>();
    private final TicketRepository repo;
    private final WithdrawalRepository withdrawals;
    private final RateTable rateTable;
    private final Crypto crypto;
    private PricingStrategy pricing;
    private final Set<String> blockedSpots = new HashSet<>();

    public ParkingLotService(String name, int floorCount, TicketRepository repo, WithdrawalRepository withdrawals,
                             RateTable rateTable, PricingStrategy pricing, Crypto crypto) {
        this.name = name; this.repo = repo; this.withdrawals = withdrawals;
        this.rateTable = rateTable; this.pricing = pricing; this.crypto = crypto;
        for (int f = 1; f <= floorCount; f++) {
            ParkingFloor floor = new ParkingFloor(f, 4, 8, 3, 1);
            floors.add(floor);
            floor.getSpots().forEach(s -> spotIndex.put(s.getId(), s));
        }
        restore();
    }

    private void restore() {
        for (Ticket t : repo.findAll()) {
            if (t.isActive()) {
                ParkingSpot s = spotIndex.get(t.getSpotId());
                if (s != null && s.canPark(t.getVehicle())) {
                    s.park(t.getVehicle());
                    activeByPlate.put(t.getVehicle().getLicensePlate(), t);
                }
            }
        }
    }

    public synchronized void setPricing(PricingStrategy p) { this.pricing = Objects.requireNonNull(p); }

    // ---------- User commands ----------
    public synchronized Ticket parkVehicle(VehicleType type, String plate, String owner) {
        Vehicle v = Vehicle.create(type, plate, owner);
        if (activeByPlate.containsKey(v.getLicensePlate()))
            throw new ParkingException("Vehicle " + v.getLicensePlate() + " is already parked", 409);
        ParkingSpot spot = findBestSpot(v)
                .orElseThrow(() -> new ParkingException("No available spot for a " + type.getLabel(), 409));
        spot.park(v);
        Ticket t = new Ticket(v, spot.getId());
        activeByPlate.put(v.getLicensePlate(), t);
        repo.save(t);
        return t;
    }

    private Optional<ParkingSpot> findBestSpot(Vehicle v) {
        return floors.stream().flatMap(f -> f.getSpots().stream())
                .filter(s -> !blockedSpots.contains(s.getId()) && s.canPark(v))
                .min(Comparator.comparingInt((ParkingSpot s) -> s.getType().getCapacity())
                        .thenComparingInt(ParkingSpot::getFloor).thenComparingInt(ParkingSpot::getNumber));
    }

    /** Exit + payment. Digital wallets/bank require an account number; cash does not. */
    public synchronized Ticket exitVehicle(String plateRaw, PaymentMethod method, String account) {
        String plate = Vehicle.normalizePlate(plateRaw);
        Ticket t = activeByPlate.get(plate);
        if (t == null) throw new ParkingException("No active ticket for " + plate, 404);
        Instant now = Instant.now();
        double fee = pricing.calculate(t.getVehicle(), Duration.between(t.getEntryTime(), now));
        String acct = validateAccount(method, account, fee);
        activeByPlate.remove(plate);
        t.close(now, fee, rateTable.getRate(t.getVehicle().getType()), method, acct);
        ParkingSpot s = spotIndex.get(t.getSpotId());
        if (s != null) s.release();
        repo.save(t);
        return t;
    }

    private static String validateAccount(PaymentMethod m, String account, double fee) {
        if (fee <= 0 || !m.needsAccount()) return account == null ? "" : mask(account.trim());
        if (account == null) throw new ParkingException("Account / mobile number is required for " + m.getLabel());
        String a = account.replaceAll("[\\s-]", "");
        if (m == PaymentMethod.BANK) {
            if (!a.matches("^[A-Za-z0-9]{8,34}$")) throw new ParkingException("Invalid bank account / IBAN");
        } else if (!a.matches("^03\\d{9}$")) throw new ParkingException("Invalid mobile number (format 03XXXXXXXXX)");
        return mask(a);
    }

    private static String mask(String a) {
        if (a.length() <= 4) return a;
        return "*".repeat(a.length() - 4) + a.substring(a.length() - 4);
    }

    // ---------- Receipts (tamper-proof, HMAC signed) ----------
    public String receiptPayload(Ticket t) {
        return String.join("|", t.getId(), t.getVehicle().getLicensePlate(), t.getSpotId(),
                t.getEntryTime().toString(), t.getExitTime() == null ? "" : t.getExitTime().toString(),
                String.valueOf(t.getFee()));
    }
    public String sign(Ticket t) { return crypto.hmac(receiptPayload(t)); }

    /** Verifies an uploaded receipt: {id, plate, signature}. */
    public synchronized Map<String, Object> verifyReceipt(String id, String plateRaw, String signature) {
        String plate = Vehicle.normalizePlate(plateRaw);
        Ticket t = repo.findAll().stream().filter(x -> x.getId().equalsIgnoreCase(id == null ? "" : id.trim())).findFirst()
                .orElseThrow(() -> new ParkingException("Ticket not found", 404));
        if (!t.getVehicle().getLicensePlate().equals(plate)) throw new ParkingException("Plate does not match ticket", 400);
        boolean valid = crypto.verify(receiptPayload(t), signature);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", valid); m.put("active", t.isActive()); m.put("ticket", t);
        return m;
    }

    // ---------- Admin commands ----------
    public synchronized Withdrawal withdraw(PaymentMethod method, String account, double amount) {
        double available = availableBalance();
        if (amount > available + 1e-9) throw new ParkingException("Insufficient balance. Available: " + available, 409);
        if (method == PaymentMethod.CASH) throw new ParkingException("Choose EasyPaisa, JazzCash or Bank");
        String acct = account == null ? "" : account.replaceAll("[\\s-]", "");
        if (method == PaymentMethod.BANK ? !acct.matches("^[A-Za-z0-9]{8,34}$") : !acct.matches("^03\\d{9}$"))
            throw new ParkingException(method == PaymentMethod.BANK ? "Invalid bank account / IBAN" : "Invalid mobile number (03XXXXXXXXX)");
        Withdrawal w = new Withdrawal(method, mask(acct), Math.round(amount * 100) / 100.0);
        withdrawals.save(w);
        return w;
    }

    public synchronized Ticket forceExit(String plateRaw) { return exitVehicle(plateRaw, PaymentMethod.CASH, ""); }

    public synchronized void setSpotBlocked(String spotId, boolean blocked) {
        ParkingSpot s = spotIndex.get(spotId);
        if (s == null) throw new ParkingException("Spot not found", 404);
        if (blocked && !s.isFree()) throw new ParkingException("Cannot block an occupied spot", 409);
        if (blocked) blockedSpots.add(spotId); else blockedSpots.remove(spotId);
    }
    public synchronized boolean isBlocked(String spotId) { return blockedSpots.contains(spotId); }

    public synchronized void setRate(VehicleType vt, double rate) { rateTable.setRate(vt, rate); }
    public RateTable getRateTable() { return rateTable; }

    // ---------- Queries ----------
    public synchronized double previewFee(String plateRaw) {
        Ticket t = activeByPlate.get(Vehicle.normalizePlate(plateRaw));
        if (t == null) throw new ParkingException("No active ticket", 404);
        return pricing.calculate(t.getVehicle(), Duration.between(t.getEntryTime(), Instant.now()));
    }
    public synchronized Optional<Ticket> findActive(String plateRaw) {
        return Optional.ofNullable(activeByPlate.get(Vehicle.normalizePlate(plateRaw)));
    }
    public synchronized Optional<Ticket> findTicket(String id) {
        if (id == null) return Optional.empty();
        String x = id.trim().toUpperCase();
        return repo.findAll().stream().filter(t -> t.getId().equals(x)).findFirst();
    }

    public String getName() { return name; }
    public List<ParkingFloor> getFloors() { return Collections.unmodifiableList(floors); }

    public synchronized List<Ticket> getActiveTickets() {
        return activeByPlate.values().stream().sorted(Comparator.comparing(Ticket::getEntryTime).reversed()).collect(Collectors.toList());
    }
    public synchronized List<Ticket> getHistory(int limit) {
        return repo.findAll().stream().filter(t -> !t.isActive())
                .sorted(Comparator.comparing(Ticket::getExitTime).reversed()).limit(limit).collect(Collectors.toList());
    }
    public synchronized List<Withdrawal> getWithdrawals() {
        List<Withdrawal> l = withdrawals.findAll(); Collections.reverse(l); return l;
    }

    public synchronized double totalRevenue() {
        return repo.findAll().stream().filter(t -> !t.isActive()).mapToDouble(Ticket::getFee).sum();
    }
    public synchronized double availableBalance() { return Math.max(0, totalRevenue() - withdrawals.total()); }

    public synchronized Map<String, Object> getStats() {
        int total = spotIndex.size();
        long free = spotIndex.values().stream().filter(s -> s.isFree() && !blockedSpots.contains(s.getId())).count();
        List<Ticket> all = repo.findAll();
        Instant dayAgo = Instant.now().minus(Duration.ofDays(1));
        double revenue = totalRevenue();
        double revenue24 = all.stream().filter(t -> !t.isActive() && t.getExitTime().isAfter(dayAgo)).mapToDouble(Ticket::getFee).sum();

        Map<String, Long> byType = new LinkedHashMap<>();
        for (VehicleType vt : VehicleType.values()) byType.put(vt.name(), 0L);
        activeByPlate.values().forEach(t -> byType.merge(t.getVehicle().getType().name(), 1L, Long::sum));

        Map<String, Double> byMethod = new LinkedHashMap<>();
        for (PaymentMethod pm : PaymentMethod.values()) byMethod.put(pm.name(), 0.0);
        all.stream().filter(t -> !t.isActive() && t.getPaymentMethod() != null)
                .forEach(t -> byMethod.merge(t.getPaymentMethod().name(), t.getFee(), Double::sum));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("totalSpots", total);
        m.put("freeSpots", free);
        m.put("blockedSpots", blockedSpots.size());
        m.put("occupiedSpots", total - free - blockedSpots.size());
        m.put("occupancyPercent", total == 0 ? 0 : Math.round((total - free - blockedSpots.size()) * 1000.0 / total) / 10.0);
        m.put("totalRevenue", revenue);
        m.put("revenue24h", revenue24);
        m.put("withdrawn", withdrawals.total());
        m.put("availableBalance", availableBalance());
        m.put("vehiclesLast24h", all.stream().filter(t -> t.getEntryTime().isAfter(dayAgo)).count());
        m.put("totalTickets", all.size());
        m.put("activeByType", byType);
        m.put("revenueByMethod", byMethod);
        return m;
    }
}
