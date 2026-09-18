import com.parking.model.*;
import com.parking.service.*;
import java.time.Duration;
public class PricingTest {
    static void check(String n, double got, double exp){ if(got!=exp) throw new AssertionError(n+": got "+got+" expected "+exp); System.out.println("PASS "+n+" = "+got); }
    public static void main(String[] a){
        PricingStrategy p = new HourlyPricing();
        Vehicle car = Vehicle.create(VehicleType.CAR,"T-1","");
        check("grace 10min", p.calculate(car, Duration.ofMinutes(10)), 0);
        check("16min -> 1h", p.calculate(car, Duration.ofMinutes(16)), 100);
        check("61min -> 2h", p.calculate(car, Duration.ofMinutes(61)), 200);
        check("15h -> cap 10h", p.calculate(car, Duration.ofHours(15)), 1000);
        check("26h -> 10+2", p.calculate(car, Duration.ofHours(26)), 1200);
        check("truck 2h", p.calculate(Vehicle.create(VehicleType.TRUCK,"T-2",""), Duration.ofHours(2)), 500);
        check("negative", p.calculate(car, Duration.ofMinutes(-5)), 0);
        System.out.println("ALL PRICING TESTS PASSED");
    }
}
