package com.parking.model;

/** Concrete vehicle: Car (Inheritance). */
public class Car extends Vehicle {
    public Car(String plate, String owner) { super(plate, owner); }
    @Override public VehicleType getType() { return VehicleType.CAR; }
    @Override public double getHourlyRate() { return 100.0; }
}
