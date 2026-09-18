package com.parking.model;

/** Supported payment / withdrawal channels. */
public enum PaymentMethod {
    CASH("Cash", false),
    EASYPAISA("EasyPaisa", true),
    JAZZCASH("JazzCash", true),
    BANK("Bank Transfer", true);

    private final String label;
    private final boolean needsAccount;

    PaymentMethod(String label, boolean needsAccount) { this.label = label; this.needsAccount = needsAccount; }
    public String getLabel() { return label; }
    public boolean needsAccount() { return needsAccount; }

    public static PaymentMethod fromString(String s) {
        if (s == null || s.trim().isEmpty()) return CASH;
        try { return valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown payment method: " + s); }
    }
}
