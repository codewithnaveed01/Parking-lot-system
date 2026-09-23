import com.parking.model.*;
import com.parking.service.*;
import java.time.Duration;

public class PricingTest {
    static void check(String label, double actual, double expected) {
        if (actual != expected) throw new AssertionError(label + ": " + actual + " (expected " + expected + ")");
        System.out.println("PASS " + label + " = " + actual);
    }
    public static void main(String[] args) {
        HourlyPricing pricing = new HourlyPricing();
        Vehicle car = Vehicle.create(VehicleType.CAR, "CAR-1", "Driver");
        check("grace 10min", pricing.calculate(car, Duration.ofMinutes(10)), 0);
        check("grace at exactly 15min", pricing.calculate(car, Duration.ofMinutes(15)), 0);
        check("just outside grace", pricing.calculate(car, Duration.ofMinutes(15).plusMillis(1)), 100);
        check("16min started hour", pricing.calculate(car, Duration.ofMinutes(16)), 100);
        check("60min exactly", pricing.calculate(car, Duration.ofMinutes(60)), 100);
        check("60min plus 1ms started second hour", pricing.calculate(car, Duration.ofMinutes(60).plusMillis(1)), 200);
        check("61min", pricing.calculate(car, Duration.ofMinutes(61)), 200);
        check("15h daily cap", pricing.calculate(car, Duration.ofHours(15)), 1000);
        check("24h daily cap", pricing.calculate(car, Duration.ofHours(24)), 1000);
        check("26h 10+2", pricing.calculate(car, Duration.ofHours(26)), 1200);
        check("truck 2h", pricing.calculate(Vehicle.create(VehicleType.TRUCK, "TRK-2", ""), Duration.ofHours(2)), 500);
        check("locked rate", pricing.calculateAtRate(Duration.ofHours(2), 125), 250);
        check("negative duration", pricing.calculate(car, Duration.ofMinutes(-5)), 0);
        System.out.println("ALL PRICING TESTS PASSED");
    }
}
