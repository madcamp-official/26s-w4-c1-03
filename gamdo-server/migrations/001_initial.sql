CREATE TABLE IF NOT EXISTS edit_jobs (
  id                TEXT PRIMARY KEY,
  device_uuid       TEXT NOT NULL,
  capture_ref       TEXT NOT NULL,
  operations_json   TEXT NOT NULL,
  style_params_json TEXT NOT NULL DEFAULT '{}',
  result_count      INTEGER NOT NULL DEFAULT 2,
  status            TEXT NOT NULL DEFAULT 'queued' CHECK (status IN
                     ('queued','processing','validating','done','failed','fallback','canceled')),
  progress_stage    TEXT,
  attempt           INTEGER NOT NULL DEFAULT 0,
  fail_reason       TEXT,
  priority          INTEGER NOT NULL DEFAULT 5,
  queued_at         INTEGER NOT NULL,
  started_at        INTEGER,
  finished_at       INTEGER,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_jobs_queue ON edit_jobs(status, priority, queued_at)
  WHERE status IN ('queued','processing');
CREATE INDEX IF NOT EXISTS ix_jobs_device ON edit_jobs(device_uuid, created_at DESC);

CREATE TABLE IF NOT EXISTS edit_job_files (
  id                TEXT PRIMARY KEY,
  job_id            TEXT NOT NULL REFERENCES edit_jobs(id),
  role              TEXT NOT NULL CHECK (role IN ('input','result')),
  kind              TEXT CHECK (kind IN ('natural','styled','generated') OR kind IS NULL),
  generative        INTEGER NOT NULL DEFAULT 0,
  seed              INTEGER,
  rank              INTEGER NOT NULL DEFAULT 0,
  validation_status TEXT NOT NULL DEFAULT 'skipped' CHECK
                    (validation_status IN ('passed','failed','skipped')),
  validation_json   TEXT NOT NULL DEFAULT '{}',
  ops_applied_json  TEXT NOT NULL DEFAULT '[]',
  storage_path      TEXT NOT NULL,
  bytes             INTEGER,
  exif_stripped     INTEGER NOT NULL DEFAULT 1,
  delivered_at      INTEGER,
  purge_after       INTEGER,
  purged_at         INTEGER,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_jobfiles_job ON edit_job_files(job_id, role, rank);
CREATE INDEX IF NOT EXISTS ix_jobfiles_purge ON edit_job_files(purge_after)
  WHERE purge_after IS NOT NULL AND purged_at IS NULL;

CREATE TABLE IF NOT EXISTS schema_migrations (
  version    TEXT PRIMARY KEY,
  applied_at INTEGER NOT NULL
);
