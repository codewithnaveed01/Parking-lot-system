package com.parking;

import com.parking.api.ApiServer;
import com.parking.repository.*;
import com.parking.service.*;
import com.parking.util.Crypto;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application entry point - wires all dependencies (Dependency Injection).
 *
 * Environment variables:
 *   PORT            - HTTP port (default 8080)            FLOORS         - number of floors (default 3)
 *   DATABASE_URL    - PostgreSQL URL; if absent, file storage in ./data is used
 *   ADMIN_USER / ADMIN_PASS - admin credentials (default admin / admin1122)
 *   RECEIPT_SECRET  - HMAC key for receipt signatures (recommended in production)
 */
public class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(env("PORT", "8080"));
        int floors = args.length > 1 ? Integer.parseInt(args[1]) : Integer.parseInt(env("FLOORS", "3"));
        Path data = Paths.get("data");

        String adminUser = env("ADMIN_USER", "admin");
        String adminPass = env("ADMIN_PASS", "admin1122");
        if ("admin1122".equals(adminPass) && System.getenv("DATABASE_URL") != null)
            System.err.println("WARNING: using default admin password in production. Set ADMIN_PASS!");

        TicketRepository tickets; WithdrawalRepository withdrawals; SettingsRepository settings;
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl != null && !dbUrl.isBlank()) {
            Database db = new Database(dbUrl.trim());
            tickets = new PostgresTicketRepository(db);
            withdrawals = new PostgresWithdrawalRepository(db);
            settings = new PostgresSettingsRepository(db);
            System.out.println("[storage] PostgreSQL");
        } else {
            tickets = new FileTicketRepository(data.resolve("tickets.db"));
            withdrawals = new FileWithdrawalRepository(data.resolve("withdrawals.db"));
            settings = new FileSettingsRepository(data.resolve("settings.properties"));
            System.out.println("[storage] local files in ./data (set DATABASE_URL for PostgreSQL)");
        }

        RateTable rates = new RateTable(settings);
        Crypto crypto = new Crypto(data.resolve("secret.key"));
        ParkingLotService service = new ParkingLotService("Five Star Parking", floors, tickets, withdrawals, rates, new HourlyPricing(rates), crypto);
        AdminAuthService auth = new AdminAuthService(adminUser, adminPass);

        ApiServer server = new ApiServer(service, auth, Paths.get("web"), port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Five Star Parking running on port " + port + "  (admin: /admin)");
    }

    private static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
}
