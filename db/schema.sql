-- Salim Habib Parking — PostgreSQL schema (reference).
-- The Java app applies all of this automatically at startup (Database.migrate),
-- so you normally do NOT need to run this file by hand. It is safe to re-run.

CREATE TABLE IF NOT EXISTS settings (
  key   VARCHAR(64) PRIMARY KEY,
  value TEXT NOT NULL
);

-- Hourly rate for every category (existing admin-set values are kept)
INSERT INTO settings (key, value) VALUES
  ('rate.MOTORCYCLE', '50.0'), ('rate.CAR', '100.0'), ('rate.VAN', '150.0'),
  ('rate.TRUCK', '250.0'), ('rate.BUS', '300.0')
ON CONFLICT (key) DO NOTHING;

CREATE TABLE IF NOT EXISTS vehicle_types (
  code        VARCHAR(16) PRIMARY KEY,           -- MOTORCYCLE, CAR, VAN, TRUCK, BUS
  label       VARCHAR(40) NOT NULL,
  size_rank   SMALLINT    NOT NULL,
  spot_prefix VARCHAR(4)  NOT NULL UNIQUE,       -- B, C, V, T, BS
  capacity    INTEGER     NOT NULL CHECK (capacity >= 0),
  hourly_rate NUMERIC(12,2) NOT NULL CHECK (hourly_rate >= 0),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO vehicle_types (code, label, size_rank, spot_prefix, capacity, hourly_rate) VALUES
  ('MOTORCYCLE', 'Motorcycle', 1, 'B',  40,  50),
  ('CAR',        'Car',        2, 'C',  30, 100),
  ('VAN',        'Van',        3, 'V',  15, 150),
  ('TRUCK',      'Truck',      4, 'T',  10, 250),
  ('BUS',        'Bus',        5, 'BS',  5, 300)
ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label, size_rank = EXCLUDED.size_rank,
  spot_prefix = EXCLUDED.spot_prefix, capacity = EXCLUDED.capacity, updated_at = now();

CREATE TABLE IF NOT EXISTS parking_spots (
  id           VARCHAR(12) PRIMARY KEY,           -- e.g. B-01, BS-05
  vehicle_type VARCHAR(16) NOT NULL REFERENCES vehicle_types(code),
  spot_number  INTEGER NOT NULL CHECK (spot_number > 0),
  blocked      BOOLEAN NOT NULL DEFAULT FALSE,
  active       BOOLEAN NOT NULL DEFAULT TRUE,     -- FALSE for bays removed from the layout (kept for history)
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (vehicle_type, spot_number)
);
CREATE INDEX IF NOT EXISTS idx_parking_spots_type ON parking_spots(vehicle_type) WHERE active;

-- 100 bays: 40 bikes, 30 cars, 15 vans, 10 trucks, 5 buses
UPDATE parking_spots SET active = FALSE WHERE active;
INSERT INTO parking_spots (id, vehicle_type, spot_number, active)
SELECT v.spot_prefix || '-' || lpad(n::text, 2, '0'), v.code, n, TRUE
FROM vehicle_types v CROSS JOIN LATERAL generate_series(1, v.capacity) AS n
ON CONFLICT (id) DO UPDATE SET vehicle_type = EXCLUDED.vehicle_type, spot_number = EXCLUDED.spot_number, active = TRUE, updated_at = now();

CREATE TABLE IF NOT EXISTS tickets (
  id              VARCHAR(16) PRIMARY KEY,
  vehicle_type    VARCHAR(16) NOT NULL,
  plate           VARCHAR(20) NOT NULL,
  owner           VARCHAR(80),
  spot_id         VARCHAR(12) NOT NULL,
  entry_time      TIMESTAMPTZ,
  exit_time       TIMESTAMPTZ,
  fee             NUMERIC(12,2) NOT NULL DEFAULT 0,
  payment_method  VARCHAR(16),
  payment_account VARCHAR(80),
  payment_ref     VARCHAR(80),
  applied_rate    NUMERIC(12,2),
  ticket_status   VARCHAR(16),
  channel         VARCHAR(16),
  created_at      TIMESTAMPTZ,
  expires_at      TIMESTAMPTZ,
  pending_ref     VARCHAR(80),
  pending_fee     NUMERIC(12,2) DEFAULT 0,
  pending_at      TIMESTAMPTZ,
  collected_by    VARCHAR(80),
  cash_tendered   NUMERIC(12,2) DEFAULT 0,
  note            TEXT,
  audit_trail     TEXT
);
CREATE INDEX IF NOT EXISTS idx_tickets_exit ON tickets(exit_time);
CREATE INDEX IF NOT EXISTS idx_tickets_vehicle_type ON tickets(vehicle_type);
CREATE UNIQUE INDEX IF NOT EXISTS idx_orbit_active_plate ON tickets(plate)   WHERE exit_time IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_orbit_active_spot  ON tickets(spot_id) WHERE exit_time IS NULL;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_tickets_vehicle_type') THEN
    ALTER TABLE tickets ADD CONSTRAINT fk_tickets_vehicle_type FOREIGN KEY (vehicle_type) REFERENCES vehicle_types(code);
  END IF;
END $$;

-- Live status of every bay
CREATE OR REPLACE VIEW spot_status AS
SELECT p.id AS spot_id, p.vehicle_type, v.label AS vehicle_label, p.spot_number, p.blocked,
       CASE WHEN t.id IS NOT NULL THEN CASE WHEN t.ticket_status = 'RESERVED' THEN 'RESERVED' ELSE 'PARKED' END
            WHEN p.blocked THEN 'BLOCKED' ELSE 'FREE' END AS status,
       t.id AS ticket_id, t.plate, t.owner, t.channel, t.created_at, t.entry_time, t.expires_at, v.hourly_rate
FROM parking_spots p
JOIN vehicle_types v ON v.code = p.vehicle_type
LEFT JOIN tickets t ON t.spot_id = p.id AND t.exit_time IS NULL
WHERE p.active;

-- Per-category totals
CREATE OR REPLACE VIEW zone_summary AS
SELECT v.code AS vehicle_type, v.label, v.capacity, v.hourly_rate,
       COUNT(*) FILTER (WHERE s.status = 'FREE')     AS free,
       COUNT(*) FILTER (WHERE s.status = 'RESERVED') AS reserved,
       COUNT(*) FILTER (WHERE s.status = 'PARKED')   AS parked,
       COUNT(*) FILTER (WHERE s.status = 'BLOCKED')  AS blocked
FROM vehicle_types v LEFT JOIN spot_status s ON s.vehicle_type = v.code
GROUP BY v.code, v.label, v.capacity, v.hourly_rate, v.size_rank
ORDER BY v.size_rank;

-- Useful queries:
--   SELECT * FROM zone_summary;
--   SELECT * FROM spot_status WHERE status <> 'FREE' ORDER BY spot_id;
