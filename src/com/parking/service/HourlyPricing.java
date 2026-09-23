package com.parking.service;

import com.parking.model.Vehicle;
import java.time.Duration;

/** Default pricing: first 15 minutes free, then per started hour, daily cap = 10 hours. */
public class HourlyPricing implements PricingStrategy {
    private static final long GRACE_MINUTES = 15;
    private static final int DAILY_CAP_HOURS = 10;
    private final RateTable rates;

    public HourlyPricing() { this(null); }
    public HourlyPricing(RateTable rates) { this.rates = rates; }

    @Override
    public double calculate(Vehicle v, Duration d) {
        return calculateAtRate(d, rates == null ? v.getHourlyRate() : rates.getRate(v.getType()));
    }

    /** Use the rate locked at reservation / entry, so later admin changes do not affect this ticket. */
    public double calculateAtRate(Duration d, double rate) {
        long millis = Math.max(0, d.toMillis());
        if (millis <= GRACE_MINUTES * 60_000L) return 0.0;
        long hours = (millis + 3_600_000L - 1) / 3_600_000L;
        long days = hours / 24;
        long rem = hours % 24;
        long billable = days * DAILY_CAP_HOURS + Math.min(rem, DAILY_CAP_HOURS);
        return Math.round(billable * rate * 100) / 100.0;
    }
}
