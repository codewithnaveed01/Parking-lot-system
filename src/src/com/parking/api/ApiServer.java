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

/** HTTP layer: static frontend + JSON REST API (public + admin). JDK only. */
public class ApiServer {
    private static final int MAX_BODY = 8192;
    private final ParkingLotService service;
    private final AdminAuthService auth;
    private final Path webRoot;
    private final HttpServer server;

    public ApiServer(ParkingLotService service, AdminAuthService auth, Path webRoot, int port) throws IOException {
        this.service = service; this.auth = auth;
        this.webRoot = webRoot.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/admin/", this::handleAdmin);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }

    // ================= PUBLIC API =================
    private void handleApi(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
        try {
            Object result;
            switch (path) {
                case "/api/stats":   get(method); result = publicStats(); break;
                case "/api/floors":  get(method); result = floorsView(false); break;
                case "/api/active":  get(method); result = publicActive(); break;
                case "/api/rates":   get(method); result = ratesView(); break;
                case "/api/methods": get(method); result = methodsView(); break;
                case "/api/fee": {
                    get(method);
                    String plate = query(ex).get("plate");
                    Ticket t = service.findActive(plate).orElseThrow(() -> new ParkingException("No active ticket for this plate", 404));
                    Map<String, Object> m = ticketView(t, true);
                    m.put("currentFee", service.previewFee(plate));
                    result = m; break;
                }
                case "/api/ticket": {   // lookup by ticket id + plate (for receipt re-download)
                    get(method);
                    Map<String, String> q = query(ex);
                    Ticket t = service.findTicket(q.get("id")).orElseThrow(() -> new ParkingException("Ticket not found", 404));
                    if (!t.getVehicle().getLicensePlate().equals(Vehicle.normalizePlate(q.get("plate"))))
                        throw new ParkingException("Plate does not match ticket", 400);
                    result = ticketView(t, true); break;
                }
                case "/api/park": {
                    post(method);
                    Map<String, String> b = body(ex);
                    result = ticketView(service.parkVehicle(VehicleType.fromString(b.get("type")), b.get("plate"), b.get("owner")), true); break;
                }
                case "/api/exit": {
                    post(method);
                    Map<String, String> b = body(ex);
                    result = ticketView(service.exitVehicle(b.get("plate"), PaymentMethod.fromString(b.get("method")), b.get("account")), true); break;
                }
                case "/api/verify": {   // upload receipt
                    post(method);
                    Map<String, String> b = body(ex);
                    Map<String, Object> r = service.verifyReceipt(b.get("id"), b.get("plate"), b.get("signature"));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("valid", r.get("valid")); m.put("active", r.get("active"));
                    m.put("ticket", ticketView((Ticket) r.get("ticket"), true));
                    result = m; break;
                }
                default: throw new ParkingException("Not found", 404);
            }
            send(ex, 200, Json.encode(result));
        } catch (Exception e) { error(ex, e); }
    }

    // ================= ADMIN API =================
    private void handleAdmin(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
        try {
            if (path.equals("/api/admin/login")) {
                post(method);
                Map<String, String> b = body(ex);
                String token = auth.login(b.get("username"), b.get("password"));
                send(ex, 200, Json.encode(Map.of("token", token, "expiresInSeconds", 8 * 3600)));
                return;
            }
            String token = bearer(ex);
            if (!auth.isValid(token)) throw new ParkingException("Unauthorized", 401);

            Object result;
            switch (path) {
                case "/api/admin/logout":  post(method); auth.logout(token); result = Map.of("ok", true); break;
                case "/api/admin/session": get(method); result = Map.of("ok", true); break;
                case "/api/admin/stats":   get(method); result = service.getStats(); break;
                case "/api/admin/history": get(method); result = ticketsView(service.getHistory(500), false); break;
                case "/api/admin/active":  get(method); result = ticketsView(service.getActiveTickets(), false); break;
                case "/api/admin/floors":  get(method); result = floorsView(true); break;
                case "/api/admin/withdrawals": get(method); result = withdrawalsView(); break;
                case "/api/admin/withdraw": {
                    post(method);
                    Map<String, String> b = body(ex);
                    double amt = parseAmount(b.get("amount"));
                    result = withdrawalView(service.withdraw(PaymentMethod.fromString(b.get("method")), b.get("account"), amt)); break;
                }
                case "/api/admin/rate": {
                    post(method);
                    Map<String, String> b = body(ex);
                    service.setRate(VehicleType.fromString(b.get("type")), parseAmount(b.get("rate")));
                    result = ratesView(); break;
                }
                case "/api/admin/force-exit": {
                    post(method);
                    result = ticketView(service.forceExit(body(ex).get("plate")), false); break;
                }
                case "/api/admin/spot": {
                    post(method);
                    Map<String, String> b = body(ex);
                    service.setSpotBlocked(b.get("spotId"), "true".equalsIgnoreCase(b.get("blocked")));
                    result = Map.of("ok", true); break;
                }
                default: throw new ParkingException("Not found", 404);
            }
            send(ex, 200, Json.encode(result));
        } catch (Exception e) { error(ex, e); }
    }

    private static double parseAmount(String s) {
        try { double d = Double.parseDouble(s.trim()); if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException(); return d; }
        catch (Exception e) { throw new ParkingException("Invalid amount"); }
    }
    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }

    // ================= helpers =================
    private void error(HttpExchange ex, Exception e) throws IOException {
        if (e instanceof ParkingException) send(ex, ((ParkingException) e).getStatus(), Json.encode(Map.of("error", e.getMessage())));
        else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException)
            send(ex, 400, Json.encode(Map.of("error", e.getMessage() == null ? "Bad request" : e.getMessage())));
        else { e.printStackTrace(); send(ex, 500, Json.encode(Map.of("error", "Internal server error"))); }
    }
    private static void get(String m)  { if (!m.equals("GET"))  throw new ParkingException("Method not allowed", 405); }
    private static void post(String m) { if (!m.equals("POST")) throw new ParkingException("Method not allowed", 405); }

    private Map<String, String> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(MAX_BODY + 1);
            if (buf.length > MAX_BODY) throw new ParkingException("Request body too large", 413);
            return Json.parseFlat(new String(buf, StandardCharsets.UTF_8));
        }
    }
    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try { m.put(java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8"), java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")); }
            catch (Exception ignored) {}
        }
        return m;
    }

    // ================= views =================
    private Map<String, Object> publicStats() {
        Map<String, Object> s = service.getStats();
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("name", "totalSpots", "freeSpots", "occupiedSpots", "occupancyPercent", "activeByType")) m.put(k, s.get(k));
        return m;
    }
    /** Public view hides plates; admin view includes them. */
    private List<Object> publicActive() {
        List<Object> out = new ArrayList<>();
        for (Ticket t : service.getActiveTickets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vehicleType", t.getVehicle().getType().name());
            m.put("spotId", t.getSpotId());
            m.put("entryTime", t.getEntryTime().toString());
            out.add(m);
        }
        return out;
    }
    private List<Object> floorsView(boolean details) {
        List<Object> out = new ArrayList<>();
        for (ParkingFloor f : service.getFloors()) {
            List<Object> spots = new ArrayList<>();
            long free = 0;
            for (ParkingSpot s : f.getSpots()) {
                Map<String, Object> m = new LinkedHashMap<>();
                boolean blocked = service.isBlocked(s.getId());
                m.put("id", s.getId()); m.put("type", s.getType().name());
                m.put("status", blocked ? "BLOCKED" : s.isFree() ? "FREE" : "OCCUPIED");
                m.put("free", s.isFree() && !blocked);
                if (s.isFree() && !blocked) free++;
                Vehicle v = s.getVehicle();
                if (v != null) { m.put("vehicleType", v.getType().name()); if (details) { m.put("plate", v.getLicensePlate()); m.put("owner", v.getOwnerName()); } }
                spots.add(m);
            }
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("level", f.getLevel()); fm.put("free", free); fm.put("total", f.getSpots().size()); fm.put("spots", spots);
            out.add(fm);
        }
        return out;
    }
    private List<Object> ticketsView(List<Ticket> ts, boolean sig) {
        List<Object> out = new ArrayList<>();
        for (Ticket t : ts) out.add(ticketView(t, sig));
        return out;
    }
    private Map<String, Object> ticketView(Ticket t, boolean withSignature) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("plate", t.getVehicle().getLicensePlate());
        m.put("owner", t.getVehicle().getOwnerName());
        m.put("vehicleType", t.getVehicle().getType().name());
        m.put("hourlyRate", t.getAppliedRate() >= 0 ? t.getAppliedRate() : service.getRateTable().getRate(t.getVehicle().getType()));
        m.put("spotId", t.getSpotId());
        m.put("entryTime", t.getEntryTime().toString());
        m.put("exitTime", t.getExitTime() == null ? null : t.getExitTime().toString());
        m.put("fee", t.getFee());
        m.put("active", t.isActive());
        m.put("paymentMethod", t.getPaymentMethod() == null ? null : t.getPaymentMethod().name());
        m.put("paymentAccount", t.getPaymentAccount());
        m.put("paymentRef", t.getPaymentRef());
        if (withSignature) m.put("signature", service.sign(t));
        return m;
    }
    private List<Object> ratesView() {
        List<Object> out = new ArrayList<>();
        service.getRateTable().all().forEach((vt, rate) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", vt.name()); m.put("label", vt.getLabel()); m.put("rate", rate); out.add(m);
        });
        return out;
    }
    private List<Object> methodsView() {
        List<Object> out = new ArrayList<>();
        for (PaymentMethod pm : PaymentMethod.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pm.name()); m.put("label", pm.getLabel()); m.put("needsAccount", pm.needsAccount()); out.add(m);
        }
        return out;
    }
    private List<Object> withdrawalsView() {
        List<Object> out = new ArrayList<>();
        for (Withdrawal w : service.getWithdrawals()) out.add(withdrawalView(w));
        return out;
    }
    private Map<String, Object> withdrawalView(Withdrawal w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId()); m.put("method", w.getMethod().name()); m.put("methodLabel", w.getMethod().getLabel());
        m.put("account", w.getAccount()); m.put("amount", w.getAmount()); m.put("time", w.getTime().toString()); m.put("reference", w.getReference());
        return m;
    }

    // ================= static =================
    private void handleStatic(HttpExchange ex) throws IOException {
        boolean head = ex.getRequestMethod().equals("HEAD");
        if (!head && !ex.getRequestMethod().equals("GET")) { send(ex, 405, "{\"error\":\"Method not allowed\"}"); return; }
        String p = ex.getRequestURI().getPath();
        if (p.equals("/") || p.isEmpty()) p = "/index.html";
        if (p.equals("/admin")) p = "/admin.html";
        Path file = webRoot.resolve(p.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) { send(ex, 404, "{\"error\":\"Not found\"}"); return; }
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", mime(file.toString()));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        security(ex);
        if (head) { ex.sendResponseHeaders(200, -1); ex.close(); return; }
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
    private static String mime(String f) {
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".css"))  return "text/css; charset=utf-8";
        if (f.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (f.endsWith(".svg"))  return "image/svg+xml";
        if (f.endsWith(".png"))  return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }
    private static void security(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "SAMEORIGIN");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; frame-ancestors 'self'");
    }
    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        security(ex);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); } else ex.close();
    }
}
