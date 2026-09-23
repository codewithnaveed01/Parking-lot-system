import com.parking.model.*;
import com.parking.repository.*;
import com.parking.service.*;
import com.parking.util.Crypto;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Run with: javac -cp build -d build test/test/*.java && java -cp build ParkingFlowTest */
public class ParkingFlowTest {
    static void check(boolean truth, String label) {
        if (!truth) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    static void rejected(Runnable op, String label) {
        try { op.run(); throw new AssertionError(label + " was accepted"); }
        catch (ParkingException expected) { check(expected.getStatus() >= 400, label); }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("orbit-test-");
        TicketRepository repo = new FileTicketRepository(dir.resolve("tickets.db"));
        SettingsRepository settings = new FileSettingsRepository(dir.resolve("settings.properties"));
        PaymentConfig config = new PaymentConfig(settings);
        Crypto crypto = new Crypto(dir.resolve("secret.key"));
        RateTable rates = new RateTable(settings);
        ParkingLotService lot = new ParkingLotService("Orbit Park", repo, settings, rates, new HourlyPricing(rates), crypto, config);
        Map<String, Object> stats = lot.getStats();
        check((int) stats.get("totalSpots") == 90 && (int) stats.get("freeSpots") == 90, "exactly 90 bays");
        Map<?, ?> zones = (Map<?, ?>) stats.get("zones");
        check(((Map<?, ?>) zones.get("MOTORCYCLE")).get("total").equals(20) && ((Map<?, ?>) zones.get("CAR")).get("total").equals(40)
                && ((Map<?, ?>) zones.get("VAN")).get("total").equals(20) && ((Map<?, ?>) zones.get("TRUCK")).get("total").equals(10), "four isolated zones");

        final ParkingLotService initial = lot;
        Ticket bike = lot.reserve(VehicleType.MOTORCYCLE, "BIKE-1", "Customer");
        String reservationSignature = lot.sign(bike, "RESERVATION");
        Ticket car = lot.reserve(VehicleType.CAR, "CAR-1", "Customer");
        Ticket van = lot.parkVehicle(VehicleType.VAN, "VAN-1", "Walk-in");
        Ticket truck = lot.parkVehicle(VehicleType.TRUCK, "TRK-1", "Walk-in");
        check(bike.getSpotId().startsWith("B-") && car.getSpotId().startsWith("C-")
                && van.getSpotId().startsWith("V-") && truck.getSpotId().startsWith("T-"), "no cross-zone allocation");
        rejected(() -> initial.parkVehicle(VehicleType.CAR, "CAR-1", "Duplicate"), "cannot double book a plate");
        rejected(() -> initial.setSpotBlocked(bike.getSpotId(), true), "cannot block a held bay");
        check((int) lot.getStats().get("freeSpots") == 86, "holds and arrivals both reduce availability");
        Ticket entered = lot.checkIn(bike.getId(), "BIKE-1");
        check(entered.getStatus() == Ticket.Status.PARKED && entered.getEntryTime() != null, "reservation check-in starts meter");
        check(lot.validSignature(entered, "RESERVATION", reservationSignature), "reservation signature still works after check-in");
        check(!lot.validSignature(entered, "RESERVATION", "modified"), "tampered signature rejected");
        rejected(() -> initial.checkIn(bike.getId(), "BIKE-1"), "cannot check in twice");
        rejected(() -> initial.submitTransfer(bike.getId(), "BIKE-1", PaymentMethod.EASYPAISA, "REF123456", ""), "unconfigured wallet cannot accept transfer");
        rejected(() -> initial.cashExit(bike.getId(), 10, "Guard"), "free exit cannot pretend cash collected");
        Ticket free = lot.cashExit(bike.getId(), 0, "Guard");
        check(free.getFee() == 0 && free.getPaymentMethod() == null && free.getStatus() == Ticket.Status.CLOSED, "free exit is recorded but not revenue");
        check(lot.getStats().get("totalRevenue").equals(0.0), "no fake revenue for free exits");

        // A previously checked-in vehicle with a known 2-hour stay: no sleeps/time manipulation needed.
        Vehicle billedVehicle = Vehicle.create(VehicleType.CAR, "CAR-PAID", "Payer");
        Ticket old = new Ticket("123456789ABC", billedVehicle, "C-02", Instant.now().minus(Duration.ofMinutes(61)), null, 0, null, "", "");
        old.setAppliedRate(100);
        repo.save(old);
        // Restart exercises file load + active restore (and does not touch the other reservations).
        lot = new ParkingLotService("Orbit Park", new FileTicketRepository(dir.resolve("tickets.db")), settings, new RateTable(settings),
                new HourlyPricing(new RateTable(settings)), new Crypto(dir.resolve("secret.key")), config);
        final ParkingLotService restarted = lot;
        check(restarted.currentFee(restarted.publicTicket(old.getId(), "CAR-PAID")) == 200, "fee persisted, 61 minutes billed as 2 started hours");
        rejected(() -> restarted.submitTransfer(old.getId(), "CAR-PAID", PaymentMethod.EASYPAISA, "ABC123456", "1234"), "no merchant account means no digital payment");
        String validIban = "PK68HABB0023057903437903";
        check(PaymentConfig.validIban(validIban), "real Pakistani IBAN checksum accepted");
        config.update("03001234567", "03123456789", validIban, "HBL", "Orbit Park");
        rejected(() -> config.update("03001234567", "03123456789", validIban, "MEEZAN", "Orbit Park"), "bank logo must match IBAN bank code");
        Ticket pending = restarted.submitTransfer(old.getId(), "CAR-PAID", PaymentMethod.EASYPAISA, "WALLET123456", "1234");
        check(pending.isPending() && pending.getPendingFee() == 200, "transfer claim stays pending (not paid)");
        check(restarted.getStats().get("totalRevenue").equals(0.0), "pending transfer never counts as received money");
        rejected(() -> restarted.cashExit(old.getId(), 200, "Guard"), "pending transfer blocks cash double charge");
        rejected(() -> restarted.submitTransfer(old.getId(), "CAR-PAID", PaymentMethod.JAZZCASH, "NEWREF123", ""), "no duplicate pending submission");
        Ticket paid = restarted.approveTransfer(old.getId(), "Admin");
        check(paid.getStatus() == Ticket.Status.CLOSED && paid.getFee() == 200 && paid.getPaymentRef().equals("WALLET123456"), "independently verified digital exit is recorded");
        check(restarted.getStats().get("totalRevenue").equals(200.0), "verified transfer appears in revenue exactly once");
        rejected(() -> restarted.approveTransfer(old.getId(), "Admin"), "cannot approve the same reference twice");
        check(restarted.validSignature(paid, "PAYMENT", restarted.sign(paid, "PAYMENT")), "payment receipt is signed");
        check(paid.getPendingAt() != null, "digital receipt retains the metering cutoff after verification");

        // An old pending claim is still charged at its original submission time,
        // not for time spent waiting for the manager. Wrong submitted fees fail.
        Ticket paused = new Ticket("PAUSED123456", Vehicle.create(VehicleType.CAR, "CAR-PAUSE", "Payer"),
                "C-03", Instant.now().minus(Duration.ofMinutes(180)), null, 0, null, "", "");
        paused.setAppliedRate(100);
        paused.submitTransfer(PaymentMethod.JAZZCASH, "PAUSE20260923", 200, "4321", Instant.now().minus(Duration.ofMinutes(119)));
        new FileTicketRepository(dir.resolve("tickets.db")).save(paused);
        ParkingLotService pausedLot = new ParkingLotService("Orbit Park", new FileTicketRepository(dir.resolve("tickets.db")),
                settings, new RateTable(settings), new HourlyPricing(new RateTable(settings)), crypto, config);
        Ticket pausedView = pausedLot.gateLookup("CAR-PAUSE");
        check(pausedLot.currentFee(pausedView) == 200, "pending transfer freezes current due at submission");
        Ticket pausedPaid = pausedLot.approveTransfer(pausedView.getId(), "Admin");
        check(pausedPaid.getFee() == 200 && pausedPaid.getPendingAt() != null, "delayed verification does not bill waiting time");
        check(pausedLot.validSignature(pausedPaid, "PAYMENT", pausedLot.sign(pausedPaid, "PAYMENT")), "billing cutoff is signed on digital receipt");

        Ticket cancelled = pausedLot.cancelReservation(car.getId(), "CAR-1", "Customer cancelled");
        check(cancelled.getStatus() == Ticket.Status.CANCELLED && pausedLot.getStats().get("freeSpots").equals(88), "cancel returns bay to capacity");
        pausedLot.setSpotBlocked("C-01", true);
        check(pausedLot.getStats().get("blockedSpots").equals(1), "maintenance block updated");
        ParkingLotService loaded = new ParkingLotService("Orbit Park", new FileTicketRepository(dir.resolve("tickets.db")),
                new FileSettingsRepository(dir.resolve("settings.properties")), new RateTable(settings), new HourlyPricing(new RateTable(settings)),
                new Crypto(dir.resolve("secret.key")), new PaymentConfig(settings));
        check(loaded.isBlocked("C-01"), "maintenance state survives restart");
        check(loaded.getPaymentConfig().enabled(PaymentMethod.EASYPAISA) && loaded.getPaymentConfig().enabled(PaymentMethod.BANK),
                "configured merchant destinations survive restart");
        check(loaded.findTicket(paid.getId()).orElseThrow().getPaymentRef().equals("WALLET123456"), "payment evidence survives restart");
        check(loaded.findTicket(paid.getId()).orElseThrow().getAuditTrail().contains("TRANSFER_VERIFIED"),
                "manager-only payment audit survives restart");

        Ticket retry = new Ticket("RETRY1234567", Vehicle.create(VehicleType.CAR, "RETRY-CAR", "Retry Payer"),
                "C-04", Instant.now().minus(Duration.ofMinutes(70)), null, 0, null, "", "");
        retry.setAppliedRate(100);
        new FileTicketRepository(dir.resolve("tickets.db")).save(retry);
        ParkingLotService retryLot = new ParkingLotService("Orbit Park", new FileTicketRepository(dir.resolve("tickets.db")),
                settings, new RateTable(settings), new HourlyPricing(new RateTable(settings)), crypto, config);
        retryLot.submitTransfer(retry.getId(), "RETRY-CAR", PaymentMethod.JAZZCASH, "REJECTEDREF2026", "");
        retryLot.rejectTransfer(retry.getId(), "Not in merchant statement");
        rejected(() -> retryLot.submitTransfer(retry.getId(), "RETRY-CAR", PaymentMethod.JAZZCASH, "REJECTEDREF2026", ""),
                "rejected reference cannot be recycled for a new claim");
        Ticket corrected = retryLot.submitTransfer(retry.getId(), "RETRY-CAR", PaymentMethod.JAZZCASH, "NEWREF2026", "");
        check(corrected.getAuditTrail().contains("REJECTEDREF2026") && corrected.getAuditTrail().contains("NEWREF2026"),
                "rejection and resubmission remain in manager audit history");

        Path migrationDir = Files.createTempDirectory("orbit-migration-");
        TicketRepository migrationRepo = new FileTicketRepository(migrationDir.resolve("tickets.db"));
        Ticket legacy = new Ticket("LEGACY123456", Vehicle.create(VehicleType.CAR, "LEG-CAR", "Older"),
                "F1-C01", Instant.now().minus(Duration.ofHours(1)), null, 0, null, "", "");
        Ticket existing = new Ticket("CURRENT12345", Vehicle.create(VehicleType.CAR, "CUR-CAR", "Current"),
                "C-01", Instant.now().minus(Duration.ofMinutes(5)), null, 0, null, "", "");
        migrationRepo.save(legacy); migrationRepo.save(existing);
        SettingsRepository migrationSettings = new FileSettingsRepository(migrationDir.resolve("settings.properties"));
        RateTable migrationRates = new RateTable(migrationSettings);
        ParkingLotService migrated = new ParkingLotService("Orbit Park", migrationRepo, migrationSettings, migrationRates,
                new HourlyPricing(migrationRates), new Crypto(migrationDir.resolve("secret.key")), new PaymentConfig(migrationSettings));
        check(migrated.gateLookup("LEG-CAR").getSpotId().equals("C-02")
                && migrated.gateLookup("CUR-CAR").getSpotId().equals("C-01"),
                "legacy floor tickets migrate without stealing a newer single-level assignment");

        Path fullDir = Files.createTempDirectory("orbit-capacity-");
        SettingsRepository fullSettings = new FileSettingsRepository(fullDir.resolve("settings.properties"));
        RateTable fullRates = new RateTable(fullSettings);
        ParkingLotService fullLot = new ParkingLotService("Orbit Park", new FileTicketRepository(fullDir.resolve("tickets.db")),
                fullSettings, fullRates, new HourlyPricing(fullRates), new Crypto(fullDir.resolve("secret.key")), new PaymentConfig(fullSettings));
        for (int i = 1; i <= 19; i++) fullLot.reserve(VehicleType.MOTORCYCLE, "FILL-B-" + i, "Rider");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try { fullLot.parkVehicle(VehicleType.MOTORCYCLE, "LAST-B-" + n, "Rider"); return true; }
                catch (ParkingException full) { return false; }
            }));
        }
        start.countDown();
        int admitted = 0;
        for (Future<Boolean> future : futures) if (future.get()) admitted++;
        pool.shutdownNow();
        Map<?, ?> fullZones = (Map<?, ?>) fullLot.getStats().get("zones");
        check(admitted == 1 && ((Map<?, ?>) fullZones.get("MOTORCYCLE")).get("free").equals(0),
                "concurrent riders cannot double-allocate the 20th bike bay");
        check(fullLot.getStats().get("freeSpots").equals(70), "full bike zone leaves other 70 vehicle-specific bays free");
        rejected(() -> fullLot.reserve(VehicleType.MOTORCYCLE, "OVERFLOW-B", "Rider"), "full bike zone never overflows into car/van/truck bays");
        System.out.println("ALL ORBIT PARK FLOW TESTS PASSED");
    }
}
