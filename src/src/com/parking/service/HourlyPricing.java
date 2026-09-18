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
        long minutes = Math.max(0, d.toMinutes());
        if (minutes <= GRACE_MINUTES) return 0.0;
        long hours = (minutes + 59) / 60;
        long days = hours / 24;
        long rem = hours % 24;
        long billable = days * DAILY_CAP_HOURS + Math.min(rem, DAILY_CAP_HOURS);
        double rate = rates == null ? v.getHourlyRate() : rates.getRate(v.getType());
        return billable * rate;
    }
}
