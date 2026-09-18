package com.parking.model;

/** Concrete vehicle: Truck (Inheritance). */
public class Truck extends Vehicle {
    public Truck(String plate, String owner) { super(plate, owner); }
    @Override public VehicleType getType() { return VehicleType.TRUCK; }
    @Override public double getHourlyRate() { return 250.0; }
}
