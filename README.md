# Orbit Park — shaded, single-level parking

A Java 17 + vanilla HTML/CSS/JS parking system with three **separate** experiences:

| Page | Audience | Actions |
|---|---|---|
| `/` | Drivers | See live availability, reserve a bay for 30 minutes, look up/cancel a booking, send a wallet/bank **transfer reference** after check-in, save/print/verify signed tickets. No public cash, check-in, or release endpoint. |
| `/gate` | Gate staff on a tablet | Check in online arrivals; create manual walk-in tickets; look up plates; take and **record real cash** (including change); offer a configured transfer option but never approve it. |
| `/admin` | Manager | All bookings and vehicle history, digital-payment verification/rejection, revenue by method, CSV exports, receipt reprints, manager-only lifecycle/payment audit for each ticket, zone/bay maintenance blocks, rates, merchant destinations, and audited no-charge releases. |

The visual identity is **red, black, and white**. The hero is a locally stored, generated, realistic rendering of a **shaded outdoor lot** with separate bike, car, van, and truck areas. No multi-storey/floor system is in use.

## Capacity and booking rules

**Exactly 90 bays, fixed by category:** Bikes `B-01…B-20` (20), cars `C-01…C-40` (40), vans `V-01…V-20` (20), trucks `T-01…T-10` (10). Allocation never spills between categories. A blocked or held bay is unavailable. The public spot map does not reveal plates or drivers.

- **Online:** reserve a currently available bay; the hold lasts **30 minutes** from the moment of booking, including when the browser is closed. Present the code and plate to the gate guard; only then does the meter start. Unclaimed holds expire automatically on the next request. The customer can cancel an unclaimed reservation using its code and plate. This is a *book-now/arrive-soon* system, not scheduling for a future date or an advance-payment service.
- **Walk-in:** gate staff assign a category-specific bay and issue an entry pass at the barrier. The meter starts immediately.
- **Pricing:** first **15 minutes free**; then per **started hour** at the hourly rate locked when booked/entered. Each 24-hour period is capped at **10 billed hours**. Default PKR/hour: bike 50, car 100, van 150, truck 250. Admin rate changes affect new bookings only. Actual fee is calculated on the server, never trusted from the browser.

### Payment truth / important limitation

There are **two working accounting paths**, but **no merchant API credentials are included in this repository**:

1. **At the gate (cash):** staff physically collect the money, enter the tendered amount on `/gate`, and confirm. The backend recalculates the fee, rejects underpayment/duplicate exits, records the guard and change, frees the bay, and issues a signed payment receipt. Zero-fee grace exits must be recorded with PKR 0 and are **not counted as revenue**.
2. **Online transfer (easypaisa / JazzCash / HBL or Meezan IBAN):** admin first saves **Orbit Park's actual merchant destination(s)** under `/admin`. The customer sends money using **their own provider/bank app** and enters the provider's transaction reference. This creates a **PENDING_VERIFICATION** record; it is *not* payment, revenue, or permission to leave. The parking meter **pauses at submission**, so waiting for manager verification does not add a new billed hour. An admin independently checks the **real merchant statement** against the reference, method, amount **and transaction time** (it must correspond to the submission, not a later transfer) and approves or rejects it in the queue. Only approval issues a paid receipt and frees the bay. Gate staff can see the status and must not release a pending vehicle. Rejection cancels the pause and recalculates the fee from check-in to the present. A reference is unique per method, including rejected attempts, and cannot be reused. Managers can inspect each ticket's private lifecycle/payment audit in its **Details** action.

**No automatic charge, hosted gateway redirect, card capture, provider webhook, refund, or bank disbursement is claimed.** Live automatic JazzCash/easypaisa/card payments would require approved merchant accounts, provider-specific API/secret keys, webhook verification, settlement/reconciliation and sandbox certification. Do **not** mark a transfer verified until it appears in the merchant's account. A no-charge admin release requires a reason and never creates fictional cash revenue. Do not configure real destination accounts on a public demo instance.

## Run

Requirements: **JDK 17+**. The JDBC driver is already in `lib/`. No Maven/npm required for the application.

```bash
./run.sh                    # http://localhost:8080 ; /gate ; /admin
./run.sh 8090               # alternate port
# Windows: run.bat 8090
```

For **local development only**, defaults are `admin / admin1122` and `guard / guard1122`. Set strong **different** passwords in all other environments; the server refuses default passwords when `DATABASE_URL` is set.

| Variable | Use |
|---|---|
| `PORT` | HTTP port (default 8080; `./run.sh [port]` overrides). Server binds `0.0.0.0`. |
| `ADMIN_USER`, `ADMIN_PASS` | Manager login. |
| `GUARD_USER`, `GUARD_PASS` | Independent gate login. |
| `DATABASE_URL` | PostgreSQL connection URL. Without it, atomic `./data/` files are used (directory must be persistent on the host). |
| `RECEIPT_SECRET` | Stable HMAC-SHA256 signing secret. Required (32+ chars) for PostgreSQL deployments. Local mode auto-generates `data/secret.key`. Back up the secret to keep older receipts verifiable. |
| `ORBIT_EASYPAISA_NUMBER`, `ORBIT_JAZZCASH_NUMBER`, `ORBIT_BANK_IBAN` | Optional initial payment destinations. Prefer configuring them in the admin UI; validate the bank/logo and beneficiary first. |

With PostgreSQL: `DATABASE_URL='postgresql://…' ADMIN_PASS='…' GUARD_PASS='…' RECEIPT_SECRET='32+ characters…' ./run.sh`. Docker (`Dockerfile`) and Railway (`railway.json`) are supported. **Run one app instance per lot**; the in-process allocation lock is not distributed across multiple replicas. Serve through HTTPS in production. The provider-transfer workflow is manual until a real merchant gateway is integrated.

## API outline

- Public: `GET /api/stats`, `/api/spots`, `/api/rates`, `/api/methods`, `/api/active`, `/api/booking?id=&plate=`; `POST /api/book`, `/api/booking/cancel`, `/api/payments/submit`, `/api/verify`.
- Guard (`Authorization: Bearer <gate token>`): `POST /api/guard/login`, `/park`, `/checkin`, `/cash-exit`, `/logout`; `GET /api/guard/session`, `/active`, `/lookup?q=`. Admin tokens can also operate a gate, but gate tokens **cannot** manage admin settings or approve transfers.
- Manager (`Authorization: Bearer <admin token>`): `POST /api/admin/login`, `/merchant`, `/rate`, `/spot`, `/payment-approve`, `/payment-reject`, `/cancel`, `/waive`, `/logout`; `GET /api/admin/stats`, `/spots`, `/active`, `/history`, `/vehicles`, `/ticket?id=`, `/audit?id=`, `/merchant`, `/session`.

Both local-file and PostgreSQL repositories persist reservations, check-ins, payments, manual collection, admin notes, and settings. An active legacy `F1-*` ticket is moved to a matching single-level zone on startup (spot assignments change; reissue any old active pass). Historical rows are left intact. Obsolete duplicate source and frontend trees were removed so `./run.sh` compiles once and the server cannot expose an outdated second UI.

## Receipts and security

- Reservation, entry and exit receipts have **phase-specific HMAC-SHA256 signatures**. An original reservation pass remains verifiable after check-in, and an entry ticket remains verifiable after exit. JSON file uploads compare the important stored fields **and** their server signature; a tampered file fails. Receipts can be downloaded as JSON, PNG and printable HTML or printed/shared from all three pages.
- Public lookups require a nonsequential random booking ID **and** plate. Public maps and lists hide plate/owner. Only masked sender last-four digits are saved; PINs, card numbers and full sender accounts are never requested.
- Staff areas have separate bearer sessions (8 hours) and login lockout. API request bodies are size-limited; data is safely escaped in browser DOM/receipts; PostgreSQL uses prepared statements. File saves use atomic replacement and surface write failures. Payment approval and capacity changes are serialized per app instance.

## Tests

```bash
./run.sh 8080             # compile + run
# In a second terminal, after the build exists:
javac -cp build -d build test/test/*.java
java -cp build PricingTest
java -cp build ParkingFlowTest
python3 test/api_flow.py  # starts its own isolated HTTP server and temporary data directory
```

`PricingTest` checks grace-period boundaries, started hours and daily caps. `ParkingFlowTest` checks dedicated capacity, receipt integrity, payments, no fake revenue and file-backed restarts. `api_flow.py` exercises auth boundaries, online/gate/admin routes, upload verification, cash, pending transfer, manager approval, reports and static pages.

## Image / mark attribution

The **Orbit Park symbol** is an original SVG in `web/img/logo.svg`; the outdoor hero was generated for this project. Vehicle pictograms are original SVGs. The wallet and bank marks in `web/img/` are the actual companies' marks, not substitute letter-badges: [JazzCash (Wikimedia)](https://commons.wikimedia.org/wiki/File:JazzCash_logo_(2025).png), [easypaisa](https://crystalpng.com/product/easypaisa-logo/), [HBL](https://crystalpng.com/product/hbl-logo/), [Meezan Bank](https://iconlogovector.com/logo/meezan-bank). Those marks remain the property of their respective owners and appear only to identify offered payment routes, not to imply a commercial partnership. Only the selected **configured** bank is offered in checkout.
