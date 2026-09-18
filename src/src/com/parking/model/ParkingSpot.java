package com.parking.model;

/** A single parking spot. State is encapsulated and mutated only via methods. */
public class ParkingSpot {
    private final String id;
    private final int floor;
    private final int number;
    private final SpotType type;
    private Vehicle vehicle;          // null when free

    public ParkingSpot(int floor, int number, SpotType type) {
        this.floor = floor;
        this.number = number;
        this.type = type;
        this.id = "F" + floor + "-" + String.format("%02d", number);
    }

    public String getId() { return id; }
    public int getFloor() { return floor; }
    public int getNumber() { return number; }
    public SpotType getType() { return type; }
    public Vehicle getVehicle() { return vehicle; }

    public synchronized boolean isFree() { return vehicle == null; }

    public synchronized boolean canPark(Vehicle v) { return isFree() && type.canFit(v.getType()); }

    public synchronized void park(Vehicle v) {
        if (!canPark(v)) throw new IllegalStateException("Spot " + id + " cannot accept " + v);
        this.vehicle = v;
    }

    public synchronized Vehicle release() {
        Vehicle v = this.vehicle;
        this.vehicle = null;
        return v;
    }
}
