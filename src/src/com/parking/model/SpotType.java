package com.parking.model;

/** Parking spot categories. Each spot type accepts vehicles up to a certain size. */
public enum SpotType {
    COMPACT(1, "Compact"),
    REGULAR(2, "Regular"),
    LARGE(3, "Large"),
    OVERSIZE(4, "Oversize");

    private final int capacity;
    private final String label;

    SpotType(int capacity, String label) { this.capacity = capacity; this.label = label; }

    public int getCapacity() { return capacity; }
    public String getLabel() { return label; }

    public boolean canFit(VehicleType vt) { return vt.getSize() <= capacity; }
}
