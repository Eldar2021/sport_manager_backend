-- Add multi-tenant + manager-listing fields to users table.
-- See review/01-auth-review.md #8 and review/05-managers-review.md

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS owner_id     UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS username     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deleted_at   TIMESTAMP;

-- Unique on username only among non-soft-deleted users
CREATE UNIQUE INDEX IF NOT EXISTS users_username_unique
    ON users (username) WHERE deleted_at IS NULL;

-- Backfill: existing OWNER users get username = local-part(email)
UPDATE users
SET username = SPLIT_PART(email, '@', 1)
WHERE username IS NULL
  AND email IS NOT NULL;

-- For existing MANAGERS without owner_id we can't auto-resolve from invite_codes
-- (the InviteCode.owner relationship is to inviter, not bound to consumer).
-- Operator must manually set owner_id for legacy MANAGER rows.
-- New MANAGER registrations (post-deployment) will set owner_id automatically.
