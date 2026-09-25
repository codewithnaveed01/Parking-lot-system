package com.parking.model;

/** A numbered, single-level bay belonging to exactly one vehicle zone. */
public class ParkingSpot {
    private final String id;
    private final int number;
    private final VehicleType zone;
    private Vehicle vehicle;

    public ParkingSpot(VehicleType zone, int number) {
        this.zone = zone;
        this.number = number;
        if (number < 1 || number > zone.getCapacity()) throw new IllegalArgumentException("Bay number out of range for " + zone.getLabel());
        id = zone.spotId(number);
    }

    public String getId() { return id; }
    public int getNumber() { return number; }
    public VehicleType getZone() { return zone; }
    public Vehicle getVehicle() { return vehicle; }
    public boolean isFree() { return vehicle == null; }
    public boolean canPark(Vehicle v) { return isFree() && zone == v.getType(); }
    public void park(Vehicle v) {
        if (!canPark(v)) throw new IllegalStateException("Spot " + id + " is unavailable for this vehicle");
        vehicle = v;
    }
    public void release() { vehicle = null; }
}
