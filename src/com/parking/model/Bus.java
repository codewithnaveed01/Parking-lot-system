package com.parking.model;

/** Concrete vehicle: Bus (Inheritance). */
public class Bus extends Vehicle {
    public Bus(String plate, String owner) { super(plate, owner); }
    @Override public VehicleType getType() { return VehicleType.BUS; }
    @Override public double getHourlyRate() { return 300.0; }
}
