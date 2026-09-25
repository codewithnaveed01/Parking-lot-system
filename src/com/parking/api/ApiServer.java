package com.parking.api;

import com.parking.model.*;
import com.parking.service.*;
import com.parking.util.Json;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Same-origin public booking, attended gate, and manager APIs. No browser can record its own cash or approve its own transfer. */
public class ApiServer {
    private static final int MAX_BODY = 8192;
    private final ParkingLotService lot;
    private final AdminAuthService admin;
    private final AdminAuthService guard;
    private final Path webRoot;
    private final HttpServer server;

    public ApiServer(ParkingLotService lot, AdminAuthService admin, AdminAuthService guard, Path webRoot, int port) throws IOException {
        this.lot = lot; this.admin = admin; this.guard = guard;
        this.webRoot = webRoot.toAbsolutePath().normalize();
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/admin/", this::handleAdmin);
        server.createContext("/api/guard/", this::handleGuard);
        server.createContext("/api/", this::handlePublic);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(12));
    }
    public void start() { server.start(); }
    public void stop() { server.stop(0); }

    private void handlePublic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        try {
            Object result;
            switch (path) {
                case "/api/stats": requireGet(method); result = publicStats(); break;
                case "/api/spots": requireGet(method); result = spotsView(false); break;
                case "/api/active": requireGet(method); result = publicActive(); break;
                case "/api/rates": requireGet(method); result = ratesView(); break;
                case "/api/methods": requireGet(method); result = methodsView(); break;
                case "/api/booking": {
                    requireGet(method); Map<String, String> q = query(ex);
                    result = ticketView(lot.publicTicket(q.get("id"), q.get("plate")), true); break;
                }
                case "/api/book": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.reserve(VehicleType.fromString(b.get("type")), b.get("plate"), b.get("owner")), true); break;
                }
                case "/api/park-now": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.parkNow(VehicleType.fromString(b.get("type")), b.get("plate"), b.get("owner")), true); break;
                }
                case "/api/booking/checkin": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.selfCheckIn(b.get("id"), b.get("plate")), true); break;
                }
                case "/api/booking/exit": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.selfExit(b.get("id"), b.get("plate")), true); break;
                }
                case "/api/booking/cancel": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.cancelReservation(b.get("id"), b.get("plate"), "Cancelled by customer"), false); break;
                }
                case "/api/payments/submit": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.submitTransfer(b.get("id"), b.get("plate"), PaymentMethod.fromString(b.get("method")),
                            b.get("reference"), b.get("lastFour")), true); break;
                }
                case "/api/verify": {
                    requirePost(method); result = verify(body(ex)); break;
                }
                default: throw new ParkingException("Not found", 404);
            }
            send(ex, 200, Json.encode(result));
        } catch (Exception e) { error(ex, e); }
    }

    private void handleGuard(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        try {
            if (path.equals("/api/guard/login")) { requirePost(method); Map<String, String> b = body(ex);
                send(ex, 200, Json.encode(Map.of("token", guard.login(b.get("username"), b.get("password")), "role", "GATE"))); return; }
            String token = bearer(ex);
            if (!guard.isValid(token) && !admin.isValid(token)) throw new ParkingException("Gate login required", 401);
            String operator = admin.isValid(token) ? "Admin: " + admin.getUsername() : "Gate: " + guard.getUsername();
            Object result;
            switch (path) {
                case "/api/guard/logout": requirePost(method); guard.logout(token); result = Map.of("ok", true); break;
                case "/api/guard/session": requireGet(method); result = Map.of("ok", true, "operator", operator); break;
                case "/api/guard/active": requireGet(method); result = ticketsView(lot.getActiveTickets()); break;
                case "/api/guard/lookup": requireGet(method); result = ticketView(lot.gateLookup(query(ex).get("q")), true); break;
                case "/api/guard/park": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.parkVehicle(VehicleType.fromString(b.get("type")), b.get("plate"), b.get("owner")), true); break;
                }
                case "/api/guard/checkin": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.checkIn(b.get("id"), b.get("plate")), true); break;
                }
                case "/api/guard/cash-exit": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.cashExit(b.get("q"), amount(b.get("received")), operator), true); break;
                }
                default: throw new ParkingException("Not found", 404);
            }
            send(ex, 200, Json.encode(result));
        } catch (Exception e) { error(ex, e); }
    }

    private void handleAdmin(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        try {
            if (path.equals("/api/admin/login")) { requirePost(method); Map<String, String> b = body(ex);
                send(ex, 200, Json.encode(Map.of("token", admin.login(b.get("username"), b.get("password")), "role", "ADMIN"))); return; }
            String token = bearer(ex);
            if (!admin.isValid(token)) throw new ParkingException("Admin login required", 401);
            Object result;
            switch (path) {
                case "/api/admin/logout": requirePost(method); admin.logout(token); result = Map.of("ok", true); break;
                case "/api/admin/session": requireGet(method); result = Map.of("ok", true); break;
                case "/api/admin/stats": requireGet(method); result = lot.getStats(); break;
                case "/api/admin/spots": requireGet(method); result = spotsView(true); break;
                case "/api/admin/active": requireGet(method); result = ticketsView(lot.getActiveTickets()); break;
                case "/api/admin/history": requireGet(method); result = ticketsView(lot.getHistory()); break;
                case "/api/admin/pending": requireGet(method); result = ticketsView(lot.getPendingPayments()); break;
                case "/api/admin/vehicles": requireGet(method); result = vehiclesView(); break;
                case "/api/admin/ticket": requireGet(method); result = ticketView(lot.findTicket(query(ex).get("id"))
                        .orElseThrow(() -> new ParkingException("Ticket not found", 404)), true); break;
                case "/api/admin/audit": requireGet(method); result = Map.of("events", lot.findTicket(query(ex).get("id"))
                        .orElseThrow(() -> new ParkingException("Ticket not found", 404)).getAuditTrail()); break;
                case "/api/admin/merchant": {
                    if (method.equals("GET")) result = lot.getPaymentConfig().view();
                    else { requirePost(method); Map<String, String> b = body(ex);
                        lot.getPaymentConfig().update(b.get("easypaisa"), b.get("jazzcash"), b.get("bankIban"), b.get("bankName"), b.get("accountName"));
                        result = lot.getPaymentConfig().view(); }
                    break;
                }
                case "/api/admin/rate": {
                    requirePost(method); Map<String, String> b = body(ex);
                    lot.setRate(VehicleType.fromString(b.get("type")), amount(b.get("rate"))); result = ratesView(); break;
                }
                case "/api/admin/spot": {
                    requirePost(method); Map<String, String> b = body(ex);
                    if (!"true".equals(b.get("blocked")) && !"false".equals(b.get("blocked"))) throw new ParkingException("blocked must be true or false");
                    lot.setSpotBlocked(b.get("spotId"), "true".equals(b.get("blocked"))); result = Map.of("ok", true); break;
                }
                case "/api/admin/payment-approve": {
                    requirePost(method); Map<String, String> b = body(ex);
                    if (!"true".equals(b.get("verified"))) throw new ParkingException("Confirm that you verified the actual transfer in the merchant account");
                    result = ticketView(lot.approveTransfer(b.get("id"), "Admin: " + admin.getUsername()), true); break;
                }
                case "/api/admin/payment-reject": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.rejectTransfer(b.get("id"), b.get("reason")), true); break;
                }
                case "/api/admin/cancel": {
                    requirePost(method); Map<String, String> b = body(ex);
                    Ticket t = lot.gateLookup(b.get("id"));
                    result = ticketView(lot.cancelReservation(t.getId(), t.getVehicle().getLicensePlate(), "Cancelled by admin"), false); break;
                }
                case "/api/admin/waive": {
                    requirePost(method); Map<String, String> b = body(ex);
                    result = ticketView(lot.waiveExit(b.get("q"), b.get("reason"), "Admin: " + admin.getUsername()), true); break;
                }
                default: throw new ParkingException("Not found", 404);
            }
            send(ex, 200, Json.encode(result));
        } catch (Exception e) { error(ex, e); }
    }

    private static double amount(String s) {
        try {
            double d = Double.parseDouble(s);
            if (!Double.isFinite(d) || d < 0 || d > 1_000_000) throw new NumberFormatException();
            return d;
        } catch (Exception e) { throw new ParkingException("Enter a valid PKR amount"); }
    }
    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }
    private static void requireGet(String m) { if (!m.equals("GET")) throw new ParkingException("Method not allowed", 405); }
    private static void requirePost(String m) { if (!m.equals("POST")) throw new ParkingException("Method not allowed", 405); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private Map<String, String> body(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json")) throw new ParkingException("Send JSON", 415);
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(MAX_BODY + 1);
            if (buf.length > MAX_BODY) throw new ParkingException("Request too large", 413);
            String json = new String(buf, StandardCharsets.UTF_8).trim();
            if (!json.startsWith("{") || !json.endsWith("}")) throw new ParkingException("Invalid JSON object");
            return Json.parseFlat(json);
        }
    }
    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String pair : q.split("&")) {
            int i = pair.indexOf('='); if (i < 1) continue;
            try { m.put(java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                    java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")); } catch (Exception ignored) {}
        }
        return m;
    }

    private Map<String, Object> publicStats() {
        Map<String, Object> all = lot.getStats(), m = new LinkedHashMap<>();
        for (String k : List.of("name", "totalSpots", "freeSpots", "reservedSpots", "occupiedSpots", "blockedSpots", "occupancyPercent", "zones")) m.put(k, all.get(k));
        return m;
    }
    private List<Object> publicActive() {
        List<Object> list = new ArrayList<>();
        for (Ticket t : lot.getActiveTickets()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("spotId", t.getSpotId()); item.put("vehicleType", t.getVehicle().getType().name());
            item.put("status", t.getStatus().name());
            item.put("since", text(t.getEntryTime() == null ? t.getCreatedAt() : t.getEntryTime()));
            list.add(item);
        }
        return list;
    }
    private List<Object> spotsView(boolean details) {
        List<Object> zones = new ArrayList<>();
        for (VehicleType type : VehicleType.values()) {
            List<Object> items = new ArrayList<>(); int free = 0;
            for (ParkingSpot spot : lot.getSpots()) {
                if (spot.getZone() != type) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                Ticket t = lot.ticketForSpot(spot.getId()).orElse(null);
                String status = lot.isBlocked(spot.getId()) ? "BLOCKED" : t != null ? t.getStatus().name() : "FREE";
                m.put("id", spot.getId()); m.put("type", type.name()); m.put("status", status);
                if (status.equals("FREE")) free++;
                if (details && t != null) { m.put("plate", t.getVehicle().getLicensePlate()); m.put("owner", t.getVehicle().getOwnerName()); m.put("bookingId", t.getId()); }
                items.add(m);
            }
            zones.add(Map.of("type", type.name(), "label", type.getLabel(), "total", items.size(), "free", free, "spots", items));
        }
        return zones;
    }
    private List<Object> ratesView() {
        List<Object> list = new ArrayList<>();
        lot.getRateTable().all().forEach((type, rate) -> list.add(Map.of("type", type.name(), "label", type.getLabel(), "rate", rate)));
        return list;
    }
    private List<Object> methodsView() {
        Map<String, Object> config = lot.getPaymentConfig().view();
        List<Object> list = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", method.name()); m.put("label", method.getLabel()); m.put("enabled", lot.getPaymentConfig().enabled(method));
            m.put("destination", method == PaymentMethod.EASYPAISA ? config.get("easypaisa") :
                    method == PaymentMethod.JAZZCASH ? config.get("jazzcash") : method == PaymentMethod.BANK ? config.get("bankIban") : "");
            m.put("accountName", config.get("accountName")); m.put("bankName", config.get("bankName"));
            list.add(m);
        }
        return list;
    }

    private Map<String, Object> ticketView(Ticket t, boolean signed) { return ticketView(t, signed, null); }
    private Map<String, Object> ticketView(Ticket t, boolean signed, String requestedPhase) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId()); m.put("plate", t.getVehicle().getLicensePlate()); m.put("owner", t.getVehicle().getOwnerName());
        m.put("vehicleType", t.getVehicle().getType().name()); m.put("spotId", t.getSpotId());
        m.put("channel", t.getChannel().name()); m.put("status", t.getStatus().name());
        m.put("createdAt", text(t.getCreatedAt())); m.put("expiresAt", t.getExpiresAt() == null ? null : t.getExpiresAt().toString());
        m.put("entryTime", t.getEntryTime() == null ? null : t.getEntryTime().toString());
        m.put("exitTime", t.getExitTime() == null ? null : t.getExitTime().toString());
        m.put("hourlyRate", t.getAppliedRate() >= 0 ? t.getAppliedRate() : lot.getRateTable().getRate(t.getVehicle().getType()));
        m.put("fee", t.getFee()); m.put("currentFee", lot.currentFee(t));
        m.put("paymentStatus", t.isPending() ? "PENDING_VERIFICATION" : t.isUnpaidAfterExit() ? "UNPAID_AFTER_EXIT" : t.getStatus() == Ticket.Status.CLOSED ?
                (t.getPaymentMethod() != null ? "PAID" : "NO_CHARGE") : t.getStatus() == Ticket.Status.PARKED ? "UNPAID" : "NOT_DUE");
        m.put("paymentMethod", t.getPaymentMethod() == null ? null : t.getPaymentMethod().name());
        m.put("paymentAccount", t.getPaymentAccount()); m.put("paymentRef", t.getPaymentRef());
        m.put("pendingRef", t.getPendingRef()); m.put("pendingFee", t.getPendingFee());
        m.put("pendingAt", t.getPendingAt() == null ? null : t.getPendingAt().toString());
        m.put("collectedBy", t.getCollectedBy()); m.put("cashTendered", t.getCashTendered());
        m.put("cashChange", t.getPaymentMethod() == PaymentMethod.CASH ? Math.round((t.getCashTendered() - t.getFee()) * 100) / 100.0 : 0);
        m.put("note", t.getNote());
        String phase = requestedPhase == null ? t.getStatus() == Ticket.Status.RESERVED ? "RESERVATION" :
                t.getStatus() == Ticket.Status.PARKED ? "ENTRY" : t.getStatus() == Ticket.Status.CLOSED
                        ? (t.isPending() || t.isUnpaidAfterExit() ? "ENTRY" : "PAYMENT") : null : requestedPhase;
        m.put("receiptType", phase);
        if (signed && phase != null) m.put("signature", lot.sign(t, phase));
        return m;
    }
    private List<Object> ticketsView(List<Ticket> tickets) {
        List<Object> out = new ArrayList<>();
        for (Ticket t : tickets) out.add(ticketView(t, true));
        return out;
    }
    private Map<String, Object> verify(Map<String, String> b) {
        Ticket t = lot.publicTicket(b.get("id"), b.get("plate"));
        String phase = b.get("receiptType");
        if (phase == null || !List.of("RESERVATION", "ENTRY", "PAYMENT").contains(phase)) throw new ParkingException("Choose a valid receipt type");
        Map<String, Object> stored = ticketView(t, true, phase);
        boolean fieldsMatch = true;
        for (String field : List.of("id", "plate", "owner", "vehicleType", "spotId", "channel", "createdAt"))
            fieldsMatch &= text(stored.get(field)).equals(b.get(field));
        if ("RESERVATION".equals(phase)) fieldsMatch &= text(stored.get("expiresAt")).equals(b.get("expiresAt"));
        if ("ENTRY".equals(phase) || "PAYMENT".equals(phase)) {
            fieldsMatch &= text(stored.get("entryTime")).equals(b.get("entryTime"));
            fieldsMatch &= sameNumber(stored.get("hourlyRate"), b.get("hourlyRate"));
        }
        if ("PAYMENT".equals(phase)) {
            for (String field : List.of("exitTime", "paymentMethod", "paymentAccount", "paymentRef", "collectedBy", "note"))
                fieldsMatch &= text(stored.get(field)).equals(text(b.get(field)));
            fieldsMatch &= sameNumber(stored.get("fee"), b.get("fee"));
            fieldsMatch &= sameNumber(stored.get("cashTendered"), b.get("cashTendered"));
            fieldsMatch &= text(stored.get("pendingAt")).equals(text(b.get("pendingAt")));
        }
        boolean valid = fieldsMatch && lot.validSignature(t, phase, b.get("signature"));
        return Map.of("valid", valid, "ticket", ticketView(t, true));
    }
    private static boolean sameNumber(Object a, String b) {
        try { return b != null && Double.compare(((Number) a).doubleValue(), Double.parseDouble(b)) == 0; }
        catch (Exception e) { return false; }
    }

    private List<Object> vehiclesView() {
        Map<String, List<Ticket>> grouped = new TreeMap<>();
        List<Ticket> all = new ArrayList<>(lot.getActiveTickets()); all.addAll(lot.getHistory());
        for (Ticket t : all) grouped.computeIfAbsent(t.getVehicle().getLicensePlate(), k -> new ArrayList<>()).add(t);
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, List<Ticket>> entry : grouped.entrySet()) {
            List<Ticket> ts = entry.getValue(); ts.sort(Comparator.comparing(Ticket::getCreatedAt));
            Ticket latest = ts.get(ts.size() - 1);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plate", entry.getKey()); m.put("owner", latest.getVehicle().getOwnerName());
            m.put("vehicleType", latest.getVehicle().getType().name()); m.put("visits", ts.size());
            m.put("totalPaid", ts.stream().filter(t -> t.getPaymentMethod() != null && t.getStatus() == Ticket.Status.CLOSED).mapToDouble(Ticket::getFee).sum());
            m.put("firstSeen", ts.get(0).getCreatedAt().toString()); m.put("lastSeen", latest.getCreatedAt().toString());
            m.put("status", latest.isActive() ? latest.getStatus().name() : "AWAY"); out.add(m);
        }
        return out;
    }

    private void error(HttpExchange ex, Exception e) throws IOException {
        if (e instanceof ParkingException) send(ex, ((ParkingException) e).getStatus(), Json.encode(Map.of("error", text(e.getMessage()))));
        else if (e instanceof IllegalArgumentException) send(ex, 400, Json.encode(Map.of("error", text(e.getMessage()))));
        else { e.printStackTrace(); send(ex, 500, Json.encode(Map.of("error", "Server could not complete the request"))); }
    }
    private void handleStatic(HttpExchange ex) throws IOException {
        boolean head = ex.getRequestMethod().equals("HEAD");
        if (!head && !ex.getRequestMethod().equals("GET")) { send(ex, 405, "{\"error\":\"Method not allowed\"}"); return; }
        String p = ex.getRequestURI().getPath();
        if (p.equals("/") || p.isEmpty()) p = "/index.html";
        if (p.equals("/admin")) p = "/admin.html";
        if (p.equals("/gate")) p = "/gate.html";
        Path file = webRoot.resolve(p.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) { send(ex, 404, "{\"error\":\"Not found\"}"); return; }
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", mime(file.toString()));
        ex.getResponseHeaders().set("Cache-Control", "no-cache"); security(ex);
        if (head) { ex.sendResponseHeaders(200, -1); ex.close(); return; }
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
    private static String mime(String f) {
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
    private static void security(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff"); h.set("Referrer-Policy", "same-origin");
        h.set("Cache-Control", "no-store");
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'self'");
    }
    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); security(ex);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }
}
