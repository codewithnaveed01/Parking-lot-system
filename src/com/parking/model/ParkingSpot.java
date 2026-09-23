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
        String prefix;
        switch (zone) {
            case MOTORCYCLE: prefix = "B"; break;
            case CAR: prefix = "C"; break;
            case VAN: prefix = "V"; break;
            case TRUCK: prefix = "T"; break;
            default: throw new IllegalArgumentException("Unknown zone");
        }
        id = prefix + "-" + String.format("%02d", number);
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
