package com.parking.model;

/** Enum encapsulating vehicle categories and their sizes (Encapsulation). */
public enum VehicleType {
    MOTORCYCLE(1, "Motorcycle"),
    CAR(2, "Car"),
    VAN(3, "Van"),
    TRUCK(4, "Truck"),
    BUS(5, "Bus");

    private final int size;
    private final String label;

    VehicleType(int size, String label) { this.size = size; this.label = label; }

    public int getSize() { return size; }
    public String getLabel() { return label; }

    public static VehicleType fromString(String s) {
        if (s == null) throw new IllegalArgumentException("Vehicle type is required");
        try { return VehicleType.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown vehicle type: " + s); }
    }
}
