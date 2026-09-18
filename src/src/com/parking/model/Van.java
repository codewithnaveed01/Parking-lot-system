package com.parking.model;

/** Concrete vehicle: Van (Inheritance). */
public class Van extends Vehicle {
    public Van(String plate, String owner) { super(plate, owner); }
    @Override public VehicleType getType() { return VehicleType.VAN; }
    @Override public double getHourlyRate() { return 150.0; }
}
