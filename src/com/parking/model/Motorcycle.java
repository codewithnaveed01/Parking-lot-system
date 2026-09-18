package com.parking.model;

/** Concrete vehicle: Motorcycle (Inheritance). */
public class Motorcycle extends Vehicle {
    public Motorcycle(String plate, String owner) { super(plate, owner); }
    @Override public VehicleType getType() { return VehicleType.MOTORCYCLE; }
    @Override public double getHourlyRate() { return 50.0; }
}
