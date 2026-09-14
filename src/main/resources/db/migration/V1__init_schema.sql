-- =============================================================================
-- V1: Initial Schema for unified-service-scheduler
-- Implements: Transactional data model with GiST exclusion constraints
-- PostgreSQL 16+ with btree_gist extension for spatiotemporal exclusion
-- =============================================================================

-- ── Prerequisites ─────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ─────────────────────────────────────────────────────────────────────────────
-- DEALERSHIP — Tenant Root Entity
-- Each dealership is an isolated business domain (noisy-neighbor boundary)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE dealership (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        VARCHAR(255) NOT NULL,
    timezone    VARCHAR(100) NOT NULL DEFAULT 'UTC',
    status      VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT dealership_status_chk CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);

COMMENT ON TABLE dealership IS 'Root tenant entity. All resources and appointments are isolated per dealership.';
COMMENT ON COLUMN dealership.timezone IS 'IANA timezone identifier (e.g., America/New_York). Used for slot display.';

-- ─────────────────────────────────────────────────────────────────────────────
-- CUSTOMER — Appointment Requester
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE customer (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    full_name    VARCHAR(255) NOT NULL,
    email        VARCHAR(320) NOT NULL,
    phone_number VARCHAR(30),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT customer_email_uq UNIQUE (email)
);

COMMENT ON TABLE customer IS 'End-customer entity. A customer can own multiple vehicles.';

-- ─────────────────────────────────────────────────────────────────────────────
-- VEHICLE — Customer-Owned Asset
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE vehicle (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID         NOT NULL,
    vin         VARCHAR(17)  NOT NULL,
    make        VARCHAR(100) NOT NULL,
    model       VARCHAR(100) NOT NULL,
    year        INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT vehicle_customer_fk FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE CASCADE,
    CONSTRAINT vehicle_vin_uq      UNIQUE (vin),
    CONSTRAINT vehicle_year_chk    CHECK (year BETWEEN 1900 AND 2100)
);

COMMENT ON COLUMN vehicle.vin IS 'Vehicle Identification Number — globally unique 17-char string.';

-- ─────────────────────────────────────────────────────────────────────────────
-- SERVICE_TYPE — Service Catalog Entry per Dealership
-- duration_minutes is critical: EndTime = StartTime + duration_minutes
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE service_type (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    dealership_id        UUID         NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    duration_minutes     INT          NOT NULL,
    required_skill_level INT          NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT service_type_dealership_fk     FOREIGN KEY (dealership_id) REFERENCES dealership(id) ON DELETE CASCADE,
    CONSTRAINT service_type_duration_chk      CHECK (duration_minutes > 0 AND duration_minutes <= 480),
    CONSTRAINT service_type_skill_level_chk   CHECK (required_skill_level BETWEEN 1 AND 10),
    CONSTRAINT service_type_name_uq           UNIQUE (dealership_id, name)
);

COMMENT ON COLUMN service_type.duration_minutes IS 'Deterministic service duration. EndTime = StartTime + duration_minutes.';
COMMENT ON COLUMN service_type.required_skill_level IS 'Minimum technician skill level for this service (1-10). TechSkill >= RequiredSkill.';

-- ─────────────────────────────────────────────────────────────────────────────
-- TECHNICIAN — Resource Entity
-- skill_level enables automated constrained allocation (TechSkill >= ServiceRequired)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE technician (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    dealership_id UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    skill_level   INT          NOT NULL DEFAULT 1,
    status        VARCHAR(50)  NOT NULL DEFAULT 'AVAILABLE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT technician_dealership_fk  FOREIGN KEY (dealership_id) REFERENCES dealership(id) ON DELETE CASCADE,
    CONSTRAINT technician_skill_chk      CHECK (skill_level BETWEEN 1 AND 10),
    CONSTRAINT technician_status_chk     CHECK (status IN ('AVAILABLE', 'ON_LEAVE', 'INACTIVE'))
);

-- Critical index for qualified technician lookups within a dealership
CREATE INDEX idx_technician_dealership_skill ON technician (dealership_id, skill_level);
COMMENT ON INDEX idx_technician_dealership_skill IS 'Powers fast qualified technician lookup: WHERE dealership_id = ? AND skill_level >= ?';

-- ─────────────────────────────────────────────────────────────────────────────
-- SERVICE_BAY — Physical Workshop Bay
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE service_bay (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    dealership_id UUID        NOT NULL,
    bay_number    VARCHAR(20) NOT NULL,
    status        VARCHAR(50) NOT NULL DEFAULT 'OPERATIONAL',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT service_bay_dealership_fk   FOREIGN KEY (dealership_id) REFERENCES dealership(id) ON DELETE CASCADE,
    CONSTRAINT service_bay_number_uq       UNIQUE (dealership_id, bay_number),
    CONSTRAINT service_bay_status_chk      CHECK (status IN ('OPERATIONAL', 'MAINTENANCE', 'CLOSED'))
);

CREATE INDEX idx_service_bay_dealership_status ON service_bay (dealership_id, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- APPOINTMENT — Core Booking Entity (Single Source of Truth)
-- 
-- CONCURRENCY CONTROL — Two-tier defense:
--   1. Redis distributed lock (application tier) — absorbs 99.99% of contention
--   2. PostgreSQL GiST exclusion constraints (storage tier) — immutable failsafe
--
-- time_slot: tsrange [start_time, end_time) — half-open interval
-- EXCLUDE USING gist prevents temporal overlaps at the storage engine level
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE appointment (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    dealership_id   UUID        NOT NULL,
    customer_id     UUID        NOT NULL,
    vehicle_id      UUID        NOT NULL,
    service_type_id UUID        NOT NULL,
    technician_id   UUID        NOT NULL,
    service_bay_id  UUID        NOT NULL,
    time_slot       TSTZRANGE   NOT NULL,  -- [start_time, end_time)
    status          VARCHAR(50) NOT NULL DEFAULT 'CONFIRMED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT appointment_dealership_fk    FOREIGN KEY (dealership_id) REFERENCES dealership(id),
    CONSTRAINT appointment_customer_fk      FOREIGN KEY (customer_id)   REFERENCES customer(id),
    CONSTRAINT appointment_vehicle_fk       FOREIGN KEY (vehicle_id)    REFERENCES vehicle(id),
    CONSTRAINT appointment_service_type_fk  FOREIGN KEY (service_type_id) REFERENCES service_type(id),
    CONSTRAINT appointment_technician_fk    FOREIGN KEY (technician_id) REFERENCES technician(id),
    CONSTRAINT appointment_service_bay_fk   FOREIGN KEY (service_bay_id) REFERENCES service_bay(id),
    CONSTRAINT appointment_status_chk       CHECK (status IN ('CONFIRMED', 'CANCELLED')),

    -- ── GiST Exclusion Constraint #1: Technician overlap prevention ──────────
    -- For status='CONFIRMED': no two appointments can share the same technician
    -- with overlapping time_slot. This is the absolute storage-tier failsafe.
    CONSTRAINT exclude_technician_overlapping_slots
        EXCLUDE USING gist (
            technician_id WITH =,
            time_slot     WITH &&
        )
        WHERE (status = 'CONFIRMED'),

    -- ── GiST Exclusion Constraint #2: Service Bay overlap prevention ─────────
    -- For status='CONFIRMED': no two appointments can share the same service bay
    -- with overlapping time_slot.
    CONSTRAINT exclude_service_bay_overlapping_slots
        EXCLUDE USING gist (
            service_bay_id WITH =,
            time_slot      WITH &&
        )
        WHERE (status = 'CONFIRMED')
);

-- Performance indexes for appointment queries
CREATE INDEX idx_appointment_dealership_status ON appointment (dealership_id, status);
CREATE INDEX idx_appointment_technician_slot   ON appointment (technician_id, time_slot) WHERE status = 'CONFIRMED';
CREATE INDEX idx_appointment_bay_slot          ON appointment (service_bay_id, time_slot) WHERE status = 'CONFIRMED';
CREATE INDEX idx_appointment_customer          ON appointment (customer_id);
CREATE INDEX idx_appointment_created_at        ON appointment (created_at DESC);

COMMENT ON COLUMN appointment.time_slot IS 'Half-open temporal interval [start, end). PostgreSQL tstzrange type. GiST-indexed for overlap detection.';
COMMENT ON CONSTRAINT exclude_technician_overlapping_slots ON appointment IS 'Storage-tier failsafe: prevents technician double-booking even if Redis lock fails.';
COMMENT ON CONSTRAINT exclude_service_bay_overlapping_slots ON appointment IS 'Storage-tier failsafe: prevents bay double-booking even if Redis lock fails.';

-- ─────────────────────────────────────────────────────────────────────────────
-- OUTBOX_EVENT — Transactional Outbox for CDC → Kafka
--
-- Pattern: atomically committed with appointment in the same PG transaction.
-- Debezium tails the WAL (logical decoding) and publishes to Kafka.
-- Partition key = dealership_id ensures per-dealer ordering.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE outbox_event (
    id             UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type VARCHAR(100) NOT NULL,  -- e.g., 'APPOINTMENT'
    aggregate_id   VARCHAR(255) NOT NULL,  -- e.g., appointment UUID
    event_type     VARCHAR(100) NOT NULL,  -- e.g., 'AppointmentBooked', 'AppointmentCancelled'
    dealership_id  UUID        NOT NULL,   -- Kafka partition key for per-dealer ordering
    payload        JSONB       NOT NULL,   -- Full event payload
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (created_at);

-- Monthly partitions for efficient pruning (Debezium reads and Kafka delivers; old rows can be dropped)
CREATE TABLE outbox_event_2026_09 PARTITION OF outbox_event
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE outbox_event_2026_10 PARTITION OF outbox_event
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE outbox_event_2026_11 PARTITION OF outbox_event
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');

CREATE TABLE outbox_event_2026_12 PARTITION OF outbox_event
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE TABLE outbox_event_2027_01 PARTITION OF outbox_event
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

-- Index for CDC processing
CREATE INDEX idx_outbox_event_aggregate ON outbox_event (aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_event_created   ON outbox_event (created_at);

COMMENT ON TABLE outbox_event IS 'Transactional outbox: events written atomically with business data. Debezium CDC tails WAL and publishes to Kafka.';
COMMENT ON COLUMN outbox_event.dealership_id IS 'Kafka partition key: ensures all events for one dealership are processed in chronological order.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Updated_at trigger function (auto-update timestamps)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dealership_updated_at   BEFORE UPDATE ON dealership   FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_customer_updated_at     BEFORE UPDATE ON customer     FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_technician_updated_at   BEFORE UPDATE ON technician   FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_service_bay_updated_at  BEFORE UPDATE ON service_bay  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_service_type_updated_at BEFORE UPDATE ON service_type FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_appointment_updated_at  BEFORE UPDATE ON appointment  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
