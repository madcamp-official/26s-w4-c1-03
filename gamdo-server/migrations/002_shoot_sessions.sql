CREATE TABLE IF NOT EXISTS shoot_sessions (
  id              TEXT PRIMARY KEY,
  owner_token     TEXT NOT NULL UNIQUE,
  policy_json     TEXT NOT NULL DEFAULT '{}',
  max_photos      INTEGER NOT NULL DEFAULT 5 CHECK (max_photos BETWEEN 1 AND 5),
  expires_at      INTEGER NOT NULL,
  claimed_at      INTEGER,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS shoot_photos (
  id              TEXT PRIMARY KEY,
  session_id      TEXT NOT NULL REFERENCES shoot_sessions(id) ON DELETE CASCADE,
  storage_path    TEXT NOT NULL,
  bytes           INTEGER NOT NULL,
  created_at      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_shoot_sessions_expiry ON shoot_sessions(expires_at);
CREATE INDEX IF NOT EXISTS ix_shoot_photos_session ON shoot_photos(session_id, created_at);
