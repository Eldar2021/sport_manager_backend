-- Subscription + Payment tables for SaaS revenue model (MOCK-only initially).
-- See review/06-subscription-review.md

CREATE TABLE IF NOT EXISTS subscriptions (
    id                      UUID PRIMARY KEY,
    owner_id                UUID NOT NULL REFERENCES users(id),
    status                  VARCHAR(32) NOT NULL,   -- ACTIVE / GRACE / EXPIRED
    source                  VARCHAR(32) NOT NULL,   -- TRIAL / PAID
    start_date              TIMESTAMP NOT NULL,
    end_date                TIMESTAMP NOT NULL,
    grace_period_ends_at    TIMESTAMP,
    created_at              TIMESTAMP,
    updated_at              TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS subscription_per_owner
    ON subscriptions (owner_id);

CREATE TABLE IF NOT EXISTS payments (
    id                          UUID PRIMARY KEY,
    subscription_id             UUID NOT NULL REFERENCES subscriptions(id),
    amount                      BIGINT NOT NULL,
    currency                    VARCHAR(3) NOT NULL,
    months                      INTEGER NOT NULL,
    table_count_snapshot        INTEGER NOT NULL,
    price_per_table_snapshot    INTEGER NOT NULL,
    status                      VARCHAR(32) NOT NULL, -- PENDING / PAID / FAILED
    payment_url                 VARCHAR(2048),
    provider                    VARCHAR(32) NOT NULL, -- MOCK / FINIK (FINIK reserved for future)
    provider_payment_id         VARCHAR(255),
    created_at                  TIMESTAMP,
    paid_at                     TIMESTAMP,
    failed_at                   TIMESTAMP,
    failure_reason              VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS payments_subscription_idx ON payments (subscription_id);

-- Backfill: existing OWNER users get a TRIAL subscription (14 days from now).
INSERT INTO subscriptions (id, owner_id, status, source, start_date, end_date, created_at, updated_at)
SELECT gen_random_uuid(), u.id, 'ACTIVE', 'TRIAL', NOW(), NOW() + INTERVAL '14 days', NOW(), NOW()
FROM users u
WHERE u.role = 'OWNER'
  AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.owner_id = u.id);
