package com.parking.service;

import com.parking.model.Vehicle;
import java.time.Duration;

/** Strategy pattern: pluggable fee calculation. */
public interface PricingStrategy {
    double calculate(Vehicle vehicle, Duration duration);
}
