-- Initial baseline schema generated from current Hibernate ddl-auto=update state
-- Captures: users, invite_codes, venues, tables, sessions

-- ─────────────────────────────────────────────────────────────────────────────
-- users
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255),
    email           VARCHAR(255) UNIQUE,
    phone           VARCHAR(255) UNIQUE,
    password        VARCHAR(255),
    role            VARCHAR(255),
    refresh_token   VARCHAR(2048),
    locked          BOOLEAN NOT NULL DEFAULT FALSE
);

-- ─────────────────────────────────────────────────────────────────────────────
-- invite_codes
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS invite_codes (
    id          UUID PRIMARY KEY,
    code        VARCHAR(255) UNIQUE,
    owner_id    UUID REFERENCES users(id),
    expires_at  TIMESTAMP,
    used        BOOLEAN NOT NULL DEFAULT FALSE
);

-- ─────────────────────────────────────────────────────────────────────────────
-- venues
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS venues (
    id          UUID PRIMARY KEY,
    owner_id    UUID NOT NULL REFERENCES users(id),
    name        VARCHAR(100) NOT NULL,
    number      INTEGER NOT NULL,
    address     VARCHAR(255),
    selected    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    deleted_at  TIMESTAMP
);

-- ─────────────────────────────────────────────────────────────────────────────
-- tables (Tables entity → DB table "tables")
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tables (
    id              UUID PRIMARY KEY,
    venue_id        UUID NOT NULL REFERENCES venues(id),
    name            VARCHAR(100),
    number          INTEGER NOT NULL,
    description     VARCHAR(500),
    tarif_amount    INTEGER NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    tarif_type      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    deleted_at      TIMESTAMP
);

-- ─────────────────────────────────────────────────────────────────────────────
-- sessions
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sessions (
    id                      UUID PRIMARY KEY,
    table_id                UUID NOT NULL REFERENCES tables(id),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    is_paused               BOOLEAN NOT NULL DEFAULT FALSE,
    started_at              TIMESTAMP NOT NULL,
    paused_at               TIMESTAMP,
    resumed_at              TIMESTAMP,
    total_paused_seconds    INTEGER NOT NULL DEFAULT 0,
    tarif_amount_snapshot   INTEGER NOT NULL,
    tarif_type_snapshot     VARCHAR(255) NOT NULL,
    manager_id              UUID REFERENCES users(id),
    status                  VARCHAR(255) NOT NULL,
    ended_at                TIMESTAMP,
    duration_seconds        INTEGER,
    total_amount            BIGINT,
    cancel_reason           VARCHAR(255)
);

-- Partial unique index: only one active session per table at any time
CREATE UNIQUE INDEX IF NOT EXISTS one_active_session_per_table
    ON sessions (table_id) WHERE is_active = TRUE;

-- Partial unique index: only one selected venue per owner
CREATE UNIQUE INDEX IF NOT EXISTS one_selected_venue_per_owner
    ON venues (owner_id) WHERE selected = TRUE AND deleted_at IS NULL;
