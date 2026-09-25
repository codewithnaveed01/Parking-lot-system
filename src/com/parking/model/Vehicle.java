package com.parking.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Abstract base class for all vehicles (Abstraction + Inheritance). */
public abstract class Vehicle {
    private static final Pattern PLATE = Pattern.compile("^[A-Z0-9\\- ]{2,15}$");

    private final String licensePlate;
    private final String ownerName;

    protected Vehicle(String licensePlate, String ownerName) {
        this.licensePlate = normalizePlate(licensePlate);
        this.ownerName = sanitize(ownerName);
    }

    public static String normalizePlate(String plate) {
        if (plate == null) throw new IllegalArgumentException("License plate is required");
        String p = plate.trim().toUpperCase();
        if (!PLATE.matcher(p).matches())
            throw new IllegalArgumentException("Invalid license plate (2-15 chars, letters/digits/dash only)");
        return p;
    }

    private static String sanitize(String s) {
        if (s == null || s.trim().isEmpty()) return "Unknown";
        String t = s.trim();
        if (t.length() > 60) t = t.substring(0, 60);
        return t;
    }

    public String getLicensePlate() { return licensePlate; }
    public String getOwnerName() { return ownerName; }

    /** Polymorphic behaviour: each subclass reports its own type and hourly rate. */
    public abstract VehicleType getType();
    public abstract double getHourlyRate();

    /** Factory method (Factory pattern). */
    public static Vehicle create(VehicleType type, String plate, String owner) {
        switch (type) {
            case MOTORCYCLE: return new Motorcycle(plate, owner);
            case CAR:        return new Car(plate, owner);
            case VAN:        return new Van(plate, owner);
            case TRUCK:      return new Truck(plate, owner);
            case BUS:        return new Bus(plate, owner);
            default: throw new IllegalArgumentException("Unsupported vehicle type");
        }
    }

    @Override public boolean equals(Object o) {
        return o instanceof Vehicle && ((Vehicle) o).licensePlate.equals(licensePlate);
    }
    @Override public int hashCode() { return Objects.hash(licensePlate); }
    @Override public String toString() { return getType().getLabel() + "[" + licensePlate + "]"; }
}
