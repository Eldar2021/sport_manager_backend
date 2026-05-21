-- Adds optional customer_name snapshot to sessions.
-- Spec: docs/session_customer_name.md
-- Written once at session start; immutable for the rest of the session lifecycle.
-- Historical rows stay NULL (no backfill — synthetic names would distort audit).

ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS customer_name VARCHAR(80);
