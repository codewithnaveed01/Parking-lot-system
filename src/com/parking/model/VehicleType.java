package com.parking.model;

/**
 * Vehicle categories with their size, bay prefix and fixed number of bays (Encapsulation).
 * This enum is the single source of truth for the lot layout; the database tables
 * vehicle_types and parking_spots are synchronised from it at startup.
 */
public enum VehicleType {
    MOTORCYCLE(1, "Motorcycle", "B", 40),
    CAR(2, "Car", "C", 30),
    VAN(3, "Van", "V", 15),
    TRUCK(4, "Truck", "T", 10),
    BUS(5, "Bus", "BS", 5);

    private final int size;
    private final String label;
    private final String spotPrefix;
    private final int capacity;

    VehicleType(int size, String label, String spotPrefix, int capacity) {
        this.size = size; this.label = label; this.spotPrefix = spotPrefix; this.capacity = capacity;
    }

    public int getSize() { return size; }
    public String getLabel() { return label; }
    public String getSpotPrefix() { return spotPrefix; }
    public int getCapacity() { return capacity; }

    /** Bay id for the n-th bay of this category, e.g. B-07 or BS-02. */
    public String spotId(int number) { return spotPrefix + "-" + String.format("%02d", number); }

    public static int totalCapacity() {
        int total = 0;
        for (VehicleType vt : values()) total += vt.capacity;
        return total;
    }

    public static VehicleType fromString(String s) {
        if (s == null) throw new IllegalArgumentException("Vehicle type is required");
        try { return VehicleType.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown vehicle type: " + s); }
    }
}
