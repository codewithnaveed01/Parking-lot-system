# ⭐ Five Star Parking — Parking Lot Management System

A complete parking-lot management system built on **Java OOP** (backend, zero frameworks) with an **HTML / CSS / JavaScript** frontend and **PostgreSQL** persistence. Includes a public user portal and a secured admin panel with payments (Cash / EasyPaisa / JazzCash / Bank) and withdrawals.

| Layer | Technology |
|---|---|
| Backend | Java 11+ · `com.sun.net.httpserver` (JDK built-in) · JDBC |
| Database | PostgreSQL (auto-migrated) — falls back to local files when no `DATABASE_URL` |
| Frontend | HTML5 · CSS3 (3D animations, light/dark theme) · Vanilla JS |
| Deploy | Docker (`Dockerfile`, `railway.json`) |

---

## 📁 Project Structure

```
parking-lot-system/
├── Dockerfile                  # Multi-stage build (JDK 17 → JRE 17 alpine)
├── railway.json                # Railway: Dockerfile builder + health check
├── run.sh / run.bat            # Local build & run (Linux/macOS / Windows)
├── lib/
│   └── postgresql-42.7.3.jar   # JDBC driver (only external dependency)
├── src/com/parking/
│   ├── Main.java               # Entry point — dependency wiring (DI)
│   ├── model/                  # Domain entities (pure OOP, no I/O)
│   │   ├── Vehicle.java        # abstract base class + factory
│   │   ├── Motorcycle.java, Car.java, Van.java, Truck.java
│   │   ├── VehicleType.java    # enum with size + label
│   │   ├── SpotType.java       # enum with capacity + canFit()
│   │   ├── ParkingSpot.java    # encapsulated, thread-safe spot state
│   │   ├── ParkingFloor.java   # composition of spots
│   │   ├── Ticket.java         # entry/exit/payment record
│   │   ├── PaymentMethod.java  # enum: CASH, EASYPAISA, JAZZCASH, BANK
│   │   └── Withdrawal.java     # admin payout record
│   ├── service/                # Business logic
│   │   ├── ParkingLotService.java  # facade: park, exit, pay, withdraw, stats
│   │   ├── PricingStrategy.java    # interface (Strategy pattern)
│   │   ├── HourlyPricing.java      # grace period + hourly + daily cap
│   │   ├── RateTable.java          # admin-editable rates
│   │   ├── AdminAuthService.java   # hashed password, tokens, lockout
│   │   └── ParkingException.java   # domain exception with HTTP status
│   ├── repository/             # Persistence (Repository pattern)
│   │   ├── TicketRepository.java, WithdrawalRepository.java, SettingsRepository.java   # interfaces
│   │   ├── FileTicketRepository.java, FileWithdrawalRepository.java, FileSettingsRepository.java
│   │   ├── PostgresTicketRepository.java, PostgresWithdrawalRepository.java, PostgresSettingsRepository.java
│   │   └── Database.java       # JDBC connection + schema migration
│   ├── api/
│   │   └── ApiServer.java      # HTTP routing, JSON REST API, static files, security headers
│   └── util/
│       ├── Json.java           # dependency-free JSON encoder/parser
│       └── Crypto.java         # HMAC-SHA256 receipts, SHA-256 hashing, tokens
├── web/                        # Frontend (served by the Java server)
│   ├── index.html              # User portal
│   ├── admin.html              # Admin panel
│   ├── style.css               # Design system, themes, 3D effects
│   ├── common.js               # Shared helpers (API, receipts, floor map)
│   ├── app.js                  # User page logic
│   ├── admin.js                # Admin page logic
│   └── img/                    # hero.jpg, logo.svg
└── test/
    └── PricingTest.java        # Unit tests for the pricing engine
```

---

## 🏛️ Architecture

Layered architecture — each layer depends only on the one below it:

```
 Browser (web/)  ──HTTP/JSON──►  api/ApiServer
                                      │
                                      ▼
                              service/ParkingLotService  ◄── PricingStrategy, RateTable, AdminAuthService
                                      │
                                      ▼
                          repository/*Repository (interfaces)
                              ├── File* implementations   (local dev)
                              └── Postgres* implementations (production)
                                      │
                                      ▼
                                  model/  (Vehicle, Ticket, ParkingSpot ...)
```

---

## 🧩 Java OOP Concepts Used

### 1. Abstraction
- **`Vehicle`** is an `abstract class`: it defines *what* every vehicle has (plate, owner, validation) and declares `getType()` / `getHourlyRate()` without implementing them.
- **Interfaces** hide implementation details: `PricingStrategy`, `TicketRepository`, `WithdrawalRepository`, `SettingsRepository`. The service layer never knows whether data lives in a file or PostgreSQL.

### 2. Inheritance
- `Motorcycle`, `Car`, `Van`, `Truck` **extend** `Vehicle`, inheriting plate validation, owner handling, `equals`/`hashCode`, and `toString`.
- `ParkingException` extends `RuntimeException` and adds an HTTP status code.

### 3. Polymorphism
- **Runtime polymorphism:** `ParkingLotService` works with `Vehicle` references; the actual subclass decides `getHourlyRate()` and `getType()`.
- **Strategy polymorphism:** `pricing.calculate(...)` — any `PricingStrategy` can be plugged in without touching the service.
- **Repository polymorphism:** `Main` injects either `File*Repository` or `Postgres*Repository`; the service code is identical.

### 4. Encapsulation
- All fields are `private`; state changes only through methods (`ParkingSpot.park()`, `release()`, `Ticket.close()`).
- Constructors validate input (`Vehicle.normalizePlate`, `Withdrawal` amount/method checks) so invalid objects can never exist.
- Collections are exposed as `Collections.unmodifiableList(...)` — callers cannot mutate internal state.
- Sensitive data is masked before storage (`mask()` in `ParkingLotService`).

### 5. Composition (HAS-A)
- `ParkingLotService` **has** `ParkingFloor`s → each **has** `ParkingSpot`s → a spot **has** a `Vehicle`.
- `Ticket` **has** a `Vehicle`; `HourlyPricing` **has** a `RateTable`.

### 6. Enums with behaviour
- `VehicleType` (size, label), `SpotType` (`canFit(VehicleType)`), `PaymentMethod` (`needsAccount()`) — type-safe constants carrying their own logic instead of magic strings.

### 7. Design Patterns
| Pattern | Where | Why |
|---|---|---|
| **Factory Method** | `Vehicle.create(type, plate, owner)` | Central place to build the right subclass |
| **Strategy** | `PricingStrategy` / `HourlyPricing` | Swap pricing rules (e.g. weekend, VIP) |
| **Repository** | `repository/` package | Isolate persistence; file ↔ database swap |
| **Facade** | `ParkingLotService` | One simple API over floors, spots, tickets, payments |
| **Dependency Injection** | `Main.java` | All collaborators passed via constructors → testable |
| **Singleton-like** | `RateTable`, `Crypto` (one instance wired in `Main`) | Shared configuration |

### 8. Other principles
- **SOLID**
  - *S*: each class has one job (`Json` = JSON, `Crypto` = crypto, `AdminAuthService` = auth)
  - *O*: add a new vehicle type or payment method by adding a class / enum constant, not by editing existing logic
  - *L*: any `Vehicle` subclass can be used wherever `Vehicle` is expected
  - *I*: small, focused interfaces (`PricingStrategy` has one method)
  - *D*: service depends on repository **interfaces**, not concrete classes
- **Immutability:** `id`, `entryTime`, `vehicle` are `final`.
- **Thread safety:** `synchronized` service methods and spot operations prevent double allocation under concurrent requests.
- **Exception handling:** domain errors → `ParkingException` with proper HTTP status (400/401/404/409/413/429); unexpected errors → 500 without leaking internals.

---

## 🔐 Security Features
- HMAC-SHA256 **digitally signed receipts**; uploaded receipts verified server-side
- Admin password stored as salted SHA-256; **bearer session tokens** (8 h); **brute-force lockout** (5 attempts → 60 s)
- Public API never exposes plates/owner names (privacy) — admin endpoints only
- Wallet/bank numbers **masked** (`*******1234`) before storage
- Input validation, request body size limits, prepared statements (no SQL injection)
- Security headers: CSP, `X-Frame-Options`, `nosniff`, `Referrer-Policy`; path-traversal protection
- Frontend renders only via `textContent` (no XSS)

---

## 🌐 REST API

**Public**
| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/stats` | Occupancy summary (no revenue) |
| GET | `/api/floors` | Floor map (spot status + vehicle type only) |
| GET | `/api/active` | Booked spots (no plates) |
| GET | `/api/rates` · `/api/methods` | Hourly rates · payment methods |
| GET | `/api/fee?plate=` | Current fee for an active ticket |
| GET | `/api/ticket?id=&plate=` | Re-fetch a ticket |
| POST | `/api/park` `{plate, owner, type}` | Issue ticket |
| POST | `/api/exit` `{plate, method, account}` | Pay & exit |
| POST | `/api/verify` `{id, plate, signature}` | Verify uploaded receipt |

**Admin** (`Authorization: Bearer <token>`)
| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/admin/login` `{username, password}` | Get token |
| POST | `/api/admin/logout` · GET `/api/admin/session` | Session |
| GET | `/api/admin/stats` | Revenue, balance, breakdown by method |
| GET | `/api/admin/floors` · `/active` · `/history` · `/withdrawals` | Full details incl. plates/owners |
| POST | `/api/admin/withdraw` `{method, account, amount}` | Payout to EasyPaisa / JazzCash / Bank |
| POST | `/api/admin/rate` `{type, rate}` | Change hourly rate |
| POST | `/api/admin/force-exit` `{plate}` | Manual exit |
| POST | `/api/admin/spot` `{spotId, blocked}` | Block / unblock a spot |

---

## ⚙️ Configuration (environment variables)

| Variable | Description | Default |
|---|---|---|
| `PORT` | HTTP port | `8080` |
| `DATABASE_URL` | PostgreSQL URL (`postgresql://user:pass@host:5432/db`) | *(none → local files in `./data`)* |
| `ADMIN_USER` | Admin username | `admin` |
| `ADMIN_PASS` | Admin password | `admin1122` |
| `RECEIPT_SECRET` | HMAC key for receipt signatures (≥16 chars) | auto-generated file |
| `FLOORS` | Number of floors (16 spots each) | `3` |

---

## ▶️ Run locally

```bash
./run.sh            # Linux / macOS   → http://localhost:8080   admin: /admin
run.bat             # Windows
```
Requires JDK 11+. No Maven/Gradle/npm needed.

With PostgreSQL:
```bash
DATABASE_URL=postgresql://user:pass@localhost:5432/parking ./run.sh
```

Run tests:
```bash
javac -cp build -d build test/PricingTest.java && java -cp build PricingTest
```

---

## 💳 Pricing rules
- First **15 minutes free**
- Billed per **started hour** at the vehicle's rate (Motorcycle 50 · Car 100 · Van 150 · Truck 250 PKR — editable by admin)
- Maximum **10 hours billed per day**

## 🅿️ Spot allocation
Best-fit: the **smallest** spot type that fits the vehicle, lowest floor first  
(Compact ← Motorcycle · Regular ← Car · Large ← Van · Oversize ← Truck; larger spots accept smaller vehicles).
