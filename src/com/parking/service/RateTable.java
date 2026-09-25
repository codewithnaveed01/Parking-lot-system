package com.parking.service;

import com.parking.model.Vehicle;
import com.parking.model.VehicleType;
import com.parking.repository.SettingsRepository;
import java.util.*;

/** Admin-configurable hourly rates, persisted through a SettingsRepository (file or database). */
public class RateTable {
    private static final String PREFIX = "rate.";
    private final Map<VehicleType, Double> rates = new EnumMap<>(VehicleType.class);
    private final SettingsRepository store;

    public RateTable(SettingsRepository store) {
        this.store = store;
        for (VehicleType vt : VehicleType.values()) rates.put(vt, Vehicle.create(vt, "RATE-1", "").getHourlyRate());
        Map<String, String> saved = store.loadAll();
        for (VehicleType vt : VehicleType.values()) {
            String v = saved.get(PREFIX + vt.name());
            if (v != null) try {
                double parsed = Double.parseDouble(v);
                if (Double.isFinite(parsed) && parsed >= 0 && parsed <= 100000) rates.put(vt, parsed);
            } catch (NumberFormatException ignored) {}
        }
        // Every category (including ones added later, e.g. BUS) must have its rate stored in settings.
        for (VehicleType vt : VehicleType.values()) {
            String v = saved.get(PREFIX + vt.name());
            if (v == null || v.trim().isEmpty() || !isValid(v)) store.put(PREFIX + vt.name(), String.valueOf(rates.get(vt)));
        }
    }

    private static boolean isValid(String v) {
        try { double d = Double.parseDouble(v); return Double.isFinite(d) && d >= 0 && d <= 100000; }
        catch (NumberFormatException e) { return false; }
    }

    public synchronized double getRate(VehicleType vt) { return rates.get(vt); }
    public synchronized Map<VehicleType, Double> all() { return new EnumMap<>(rates); }

    public synchronized void setRate(VehicleType vt, double rate) {
        if (!Double.isFinite(rate) || rate < 0 || rate > 100000) throw new IllegalArgumentException("Rate must be between 0 and 100000");
        store.put(PREFIX + vt.name(), String.valueOf(rate));
        rates.put(vt, rate);
    }
}
