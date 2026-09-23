package com.parking;

import com.parking.api.ApiServer;
import com.parking.repository.*;
import com.parking.service.*;
import com.parking.util.Crypto;
import java.nio.file.*;

/** Orbit Park: 90 dedicated, shaded, outdoor bays. No floors or fictitious instant charges. */
public class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 && !args[0].isBlank() ? Integer.parseInt(args[0]) : Integer.parseInt(env("PORT", "8080"));
        Path data = Paths.get("data");
        String dbUrl = System.getenv("DATABASE_URL");
        String adminPass = env("ADMIN_PASS", "admin1122");
        String guardPass = env("GUARD_PASS", "guard1122");
        if (dbUrl != null && !dbUrl.isBlank()) {
            if (adminPass.equals("admin1122") || guardPass.equals("guard1122"))
                throw new IllegalStateException("Set strong ADMIN_PASS and GUARD_PASS before using PostgreSQL in production");
            if (System.getenv("RECEIPT_SECRET") == null || System.getenv("RECEIPT_SECRET").length() < 32)
                throw new IllegalStateException("Set a stable RECEIPT_SECRET (32+ characters) in production");
        } else if (adminPass.equals("admin1122") || guardPass.equals("guard1122")) {
            System.err.println("DEVELOPMENT ONLY: default staff passwords. Set ADMIN_PASS and GUARD_PASS before making the server public.");
        }

        TicketRepository tickets;
        SettingsRepository settings;
        if (dbUrl != null && !dbUrl.isBlank()) {
            Database db = new Database(dbUrl.trim());
            tickets = new PostgresTicketRepository(db);
            settings = new PostgresSettingsRepository(db);
            System.out.println("[storage] PostgreSQL");
        } else {
            tickets = new FileTicketRepository(data.resolve("tickets.db"));
            settings = new FileSettingsRepository(data.resolve("settings.properties"));
            System.out.println("[storage] local files in ./data");
        }
        RateTable rates = new RateTable(settings);
        PaymentConfig paymentConfig = new PaymentConfig(settings);
        ParkingLotService lot = new ParkingLotService("Orbit Park", tickets, settings, rates,
                new HourlyPricing(rates), new Crypto(data.resolve("secret.key")), paymentConfig);
        AdminAuthService admin = new AdminAuthService(env("ADMIN_USER", "admin"), adminPass);
        AdminAuthService guard = new AdminAuthService(env("GUARD_USER", "guard"), guardPass);
        ApiServer server = new ApiServer(lot, admin, guard, Paths.get("web"), port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Orbit Park listening on 0.0.0.0:" + port + " | / (online) | /gate (staff) | /admin (manager)");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
