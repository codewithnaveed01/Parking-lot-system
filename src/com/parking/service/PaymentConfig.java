package com.parking.service;

import com.parking.model.PaymentMethod;
import com.parking.repository.SettingsRepository;
import com.parking.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** Merchant payout destinations. A transfer is NEVER considered paid just because a reference was submitted. */
public final class PaymentConfig {
    private static final String KEY = "merchant.config";
    private final SettingsRepository store;
    private String easypaisa, jazzcash, bankIban, bankName, accountName;

    public PaymentConfig(SettingsRepository store) {
        this.store = store;
        Map<String, String> saved = Json.parseFlat(store.loadAll().get(KEY));
        easypaisa = saved.getOrDefault("easypaisa", env("ORBIT_EASYPAISA_NUMBER"));
        jazzcash = saved.getOrDefault("jazzcash", env("ORBIT_JAZZCASH_NUMBER"));
        bankIban = saved.getOrDefault("bankIban", env("ORBIT_BANK_IBAN").replaceAll("[\\s-]", "").toUpperCase());
        bankName = saved.getOrDefault("bankName", bankIban.length() >= 8 && bankIban.substring(4, 8).equals("MEZN") ? "MEEZAN" : "HBL");
        accountName = saved.getOrDefault("accountName", "Orbit Park");
    }

    private static String env(String name) { return System.getenv(name) == null ? "" : System.getenv(name).trim(); }
    public synchronized void update(String ep, String jc, String iban, String bank, String name) {
        ep = ep == null ? "" : ep.trim(); jc = jc == null ? "" : jc.trim();
        iban = iban == null ? "" : iban.replaceAll("[\\s-]", "").toUpperCase();
        bank = bank == null ? "" : bank.trim().toUpperCase();
        name = name == null ? "" : name.trim();
        if (!ep.isEmpty() && !ep.matches("03\\d{9}")) throw new ParkingException("EasyPaisa number must be 03XXXXXXXXX");
        if (!jc.isEmpty() && !jc.matches("03\\d{9}")) throw new ParkingException("JazzCash number must be 03XXXXXXXXX");
        if (!iban.isEmpty() && !validIban(iban)) throw new ParkingException("Enter a valid 24-character Pakistani IBAN");
        if (!bank.equals("HBL") && !bank.equals("MEEZAN")) throw new ParkingException("Select HBL or Meezan Bank");
        if (!iban.isEmpty() && !(bank.equals("HBL") ? iban.substring(4, 8).equals("HABB") : iban.substring(4, 8).equals("MEZN")))
            throw new ParkingException("The IBAN bank code does not match the selected bank logo");
        if (name.length() < 2 || name.length() > 60 || name.contains("|") || name.contains("\n"))
            throw new ParkingException("Account name must be 2–60 characters");
        Map<String, String> data = new LinkedHashMap<>();
        data.put("easypaisa", ep); data.put("jazzcash", jc); data.put("bankIban", iban);
        data.put("bankName", bank); data.put("accountName", name);
        store.put(KEY, Json.encode(data));
        easypaisa = ep; jazzcash = jc; bankIban = iban; bankName = bank; accountName = name;
    }

    /** Pakistan IBAN: PK + check digits + four-letter bank code + 16 account characters; MOD-97 check. */
    public static boolean validIban(String iban) {
        if (iban == null || !iban.matches("PK[0-9]{2}[A-Z]{4}[A-Z0-9]{16}")) return false;
        String moved = iban.substring(4) + iban.substring(0, 4);
        int mod = 0;
        for (int i = 0; i < moved.length(); i++) {
            char c = moved.charAt(i);
            String digits = c >= 'A' && c <= 'Z' ? Integer.toString(c - 'A' + 10) : String.valueOf(c);
            for (int j = 0; j < digits.length(); j++) mod = (mod * 10 + digits.charAt(j) - '0') % 97;
        }
        return mod == 1;
    }

    public synchronized boolean enabled(PaymentMethod method) {
        switch (method) {
            case EASYPAISA: return easypaisa.matches("03\\d{9}");
            case JAZZCASH: return jazzcash.matches("03\\d{9}");
            case BANK: return validIban(bankIban) && (bankName.equals("HBL") && bankIban.substring(4, 8).equals("HABB")
                    || bankName.equals("MEEZAN") && bankIban.substring(4, 8).equals("MEZN"));
            default: return false; // cash only at the attended gate
        }
    }

    public synchronized Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("easypaisa", easypaisa); m.put("jazzcash", jazzcash);
        m.put("bankIban", bankIban); m.put("bankName", bankName); m.put("accountName", accountName);
        return m;
    }
}
