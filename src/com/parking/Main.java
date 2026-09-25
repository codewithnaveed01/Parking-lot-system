package com.parking;

import com.parking.api.ApiServer;
import com.parking.repository.*;
import com.parking.service.*;
import com.parking.util.Crypto;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Salim Habib Parking: 100 dedicated outdoor bays (40 bikes, 30 cars, 15 vans, 10 trucks, 5 buses). Uses PostgreSQL when configured, local files otherwise. */
public class Main {
    public static final String APP_NAME = "Salim Habib Parking";
    private static final String DEFAULT_ADMIN_PASS = "admin1122";
    private static final String DEFAULT_GUARD_PASS = "guard1122";

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 && !args[0].isBlank() ? Integer.parseInt(args[0].trim()) : Integer.parseInt(env("PORT", "8080").trim());
        Path data = Paths.get("data");
        String dbUrl = Database.fromEnvironment();
        String adminPass = env("ADMIN_PASS", DEFAULT_ADMIN_PASS);
        String guardPass = env("GUARD_PASS", DEFAULT_GUARD_PASS);
        if (adminPass.equals(DEFAULT_ADMIN_PASS) || guardPass.equals(DEFAULT_GUARD_PASS))
            System.err.println("WARNING: default staff passwords in use. Set ADMIN_PASS and GUARD_PASS environment variables.");

        TicketRepository tickets;
        SettingsRepository settings;
        Crypto crypto;
        if (dbUrl != null) {
            Database db = new Database(dbUrl);
            tickets = new PostgresTicketRepository(db);
            settings = new PostgresSettingsRepository(db);
            crypto = databaseCrypto(settings);
            System.out.println("[storage] PostgreSQL");
        } else {
            tickets = new FileTicketRepository(data.resolve("tickets.db"));
            settings = new FileSettingsRepository(data.resolve("settings.properties"));
            crypto = new Crypto(data.resolve("secret.key"));
            System.out.println("[storage] local files in ./data (set DATABASE_URL to use PostgreSQL)");
        }
        RateTable rates = new RateTable(settings);
        PaymentConfig paymentConfig = new PaymentConfig(settings);
        ParkingLotService lot = new ParkingLotService(APP_NAME, tickets, settings, rates,
                new HourlyPricing(rates), crypto, paymentConfig);
        AdminAuthService admin = new AdminAuthService(env("ADMIN_USER", "admin"), adminPass);
        AdminAuthService guard = new AdminAuthService(env("GUARD_USER", "guard"), guardPass);
        ApiServer server = new ApiServer(lot, admin, guard, Paths.get("web"), port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println(APP_NAME + " listening on 0.0.0.0:" + port + " | / (online) | /gate (staff) | /admin (manager)");
    }

    /**
     * Container file systems (Railway) are wiped on every deploy, so the receipt signing key must
     * live in the database: RECEIPT_SECRET wins, otherwise a generated key is stored once in settings.
     */
    private static Crypto databaseCrypto(SettingsRepository settings) {
        String env = System.getenv("RECEIPT_SECRET");
        if (env != null && env.length() >= 16) return new Crypto(env.getBytes(StandardCharsets.UTF_8));
        String stored = settings.loadAll().get("receipt.secret");
        if (stored == null || stored.length() < 16) {
            stored = Crypto.newSecret();
            settings.put("receipt.secret", stored);
            System.out.println("[security] generated receipt signing key and stored it in the database");
        }
        return new Crypto(stored.getBytes(StandardCharsets.UTF_8));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
