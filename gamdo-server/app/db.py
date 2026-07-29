from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path
from typing import Any


SERVER_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DB_PATH = SERVER_ROOT / "data" / "gamdo.sqlite3"


def now_ms() -> int:
    return int(time.time() * 1000)


RESULT_RETENTION_MS = 24 * 60 * 60 * 1000
JOB_METADATA_RETENTION_MS = 7 * 24 * 60 * 60 * 1000


class Database:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or DEFAULT_DB_PATH

    def connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    def initialize(self) -> None:
        migration = SERVER_ROOT / "migrations" / "001_initial.sql"
        with self.connect() as connection:
            connection.executescript(migration.read_text(encoding="utf-8"))
            connection.execute(
                "INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (?, ?)",
                ("001_initial", now_ms()),
            )

    def insert_job(
        self,
        *,
        job_id: str,
        device_uuid: str,
        capture_ref: str,
        operations: list[dict[str, Any]],
        style_params: dict[str, Any],
        result_count: int,
        input_file_id: str,
        storage_path: str,
        bytes_count: int,
    ) -> None:
        timestamp = now_ms()
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO edit_jobs(
                    id, device_uuid, capture_ref, operations_json, style_params_json,
                    result_count, status, queued_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?)
                """,
                (
                    job_id,
                    device_uuid,
                    capture_ref,
                    json.dumps(operations, ensure_ascii=False, separators=(",", ":")),
                    json.dumps(style_params, ensure_ascii=False, separators=(",", ":")),
                    result_count,
                    timestamp,
                    timestamp,
                    timestamp,
                ),
            )
            connection.execute(
                """
                INSERT INTO edit_job_files(
                    id, job_id, role, storage_path, bytes, exif_stripped, created_at, updated_at
                ) VALUES (?, ?, 'input', ?, ?, 1, ?, ?)
                """,
                (input_file_id, job_id, storage_path, bytes_count, timestamp, timestamp),
            )

    def get_job(self, job_id: str) -> sqlite3.Row | None:
        with self.connect() as connection:
            return connection.execute(
                "SELECT * FROM edit_jobs WHERE id = ?", (job_id,)
            ).fetchone()

    def get_results(self, job_id: str) -> list[sqlite3.Row]:
        with self.connect() as connection:
            return connection.execute(
                "SELECT * FROM edit_job_files WHERE job_id = ? AND role = 'result' ORDER BY rank",
                (job_id,),
            ).fetchall()

    def get_input_file(self, job_id: str) -> sqlite3.Row | None:
        with self.connect() as connection:
            return connection.execute(
                "SELECT * FROM edit_job_files WHERE job_id = ? AND role = 'input' LIMIT 1",
                (job_id,),
            ).fetchone()

    def insert_result(
        self,
        *,
        file_id: str,
        job_id: str,
        storage_path: str,
        bytes_count: int,
        seed: int,
        validation_json: dict[str, Any],
        kind: str = "generated",
    ) -> None:
        timestamp = now_ms()
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO edit_job_files(
                    id, job_id, role, kind, generative, seed, rank,
                    validation_status, validation_json, storage_path, bytes,
                    exif_stripped, created_at, updated_at
                ) VALUES (?, ?, 'result', ?, 1, ?,
                          (SELECT COUNT(*) FROM edit_job_files WHERE job_id = ? AND role = 'result'),
                          'passed', ?, ?, ?, 1, ?, ?)
                """,
                (
                    file_id,
                    job_id,
                    kind,
                    seed,
                    job_id,
                    json.dumps(validation_json, ensure_ascii=False, separators=(",", ":")),
                    storage_path,
                    bytes_count,
                    timestamp,
                    timestamp,
                ),
            )

    def claim_next_queued(self) -> sqlite3.Row | None:
        timestamp = now_ms()
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            job = connection.execute(
                """
                SELECT * FROM edit_jobs
                WHERE status = 'queued'
                ORDER BY priority DESC, queued_at ASC
                LIMIT 1
                """
            ).fetchone()
            if job is None:
                return None
            connection.execute(
                """
                UPDATE edit_jobs
                SET status = 'processing', progress_stage = 'generating',
                    started_at = COALESCE(started_at, ?), updated_at = ?
                WHERE id = ? AND status = 'queued'
                """,
                (timestamp, timestamp, job["id"]),
            )
            return connection.execute(
                "SELECT * FROM edit_jobs WHERE id = ?", (job["id"],)
            ).fetchone()

    def transition_job(
        self,
        job_id: str,
        status: str,
        *,
        fail_reason: str | None = None,
        progress_stage: str | None = None,
    ) -> None:
        timestamp = now_ms()
        with self.connect() as connection:
            connection.execute(
                """
                UPDATE edit_jobs
                SET status = ?, fail_reason = ?, progress_stage = ?,
                    started_at = CASE WHEN ? = 'processing' AND started_at IS NULL THEN ? ELSE started_at END,
                    finished_at = CASE WHEN ? IN ('done', 'failed', 'fallback', 'canceled') THEN ? ELSE finished_at END,
                    updated_at = ?
                WHERE id = ?
                """,
                (
                    status,
                    fail_reason,
                    progress_stage,
                    status,
                    timestamp,
                    status,
                    timestamp,
                    timestamp,
                    job_id,
                ),
            )

    def schedule_input_purge(self, job_id: str) -> None:
        timestamp = now_ms()
        with self.connect() as connection:
            connection.execute(
                """
                UPDATE edit_job_files
                SET purge_after = ?, updated_at = ?
                WHERE job_id = ? AND role = 'input' AND purged_at IS NULL
                """,
                (timestamp, timestamp, job_id),
            )

    def mark_results_delivered(self, job_id: str) -> None:
        timestamp = now_ms()
        with self.connect() as connection:
            connection.execute(
                """
                UPDATE edit_job_files
                SET delivered_at = COALESCE(delivered_at, ?),
                    purge_after = COALESCE(purge_after, ?), updated_at = ?
                WHERE job_id = ? AND role = 'result' AND purged_at IS NULL
                """,
                (timestamp, timestamp + RESULT_RETENTION_MS, timestamp, job_id),
            )

    def recover_stale_jobs(self, timeout_ms: int) -> int:
        cutoff = now_ms() - timeout_ms
        timestamp = now_ms()
        with self.connect() as connection:
            cursor = connection.execute(
                """
                UPDATE edit_jobs
                SET status = 'failed', fail_reason = 'processing_timeout',
                    finished_at = ?, updated_at = ?
                WHERE status IN ('processing', 'validating')
                  AND updated_at <= ?
                """,
                (timestamp, timestamp, cutoff),
            )
            return cursor.rowcount

    def expired_files(self) -> list[sqlite3.Row]:
        with self.connect() as connection:
            return connection.execute(
                """
                SELECT * FROM edit_job_files
                WHERE purge_after IS NOT NULL AND purge_after <= ? AND purged_at IS NULL
                """,
                (now_ms(),),
            ).fetchall()

    def mark_file_purged(self, file_id: str) -> None:
        timestamp = now_ms()
        with self.connect() as connection:
            connection.execute(
                "UPDATE edit_job_files SET purged_at = ?, updated_at = ? WHERE id = ?",
                (timestamp, timestamp, file_id),
            )

    def purge_old_job_metadata(self, retention_ms: int = JOB_METADATA_RETENTION_MS) -> int:
        cutoff = now_ms() - retention_ms
        with self.connect() as connection:
            old_jobs = connection.execute(
                """
                SELECT id FROM edit_jobs
                WHERE status IN ('done', 'failed', 'fallback', 'canceled')
                  AND finished_at IS NOT NULL AND finished_at <= ?
                """,
                (cutoff,),
            ).fetchall()
            for job in old_jobs:
                connection.execute("DELETE FROM edit_job_files WHERE job_id = ?", (job["id"],))
                connection.execute("DELETE FROM edit_jobs WHERE id = ?", (job["id"],))
            return len(old_jobs)
