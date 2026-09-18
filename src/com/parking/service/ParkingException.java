package com.parking.service;

/** Domain exception for business-rule violations. */
public class ParkingException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    public ParkingException(String msg) { this(msg, 400); }
    public ParkingException(String msg, int status) { super(msg); this.status = status; }
    public int getStatus() { return status; }
}
