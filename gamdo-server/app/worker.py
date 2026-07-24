from __future__ import annotations

import time
from pathlib import Path
from threading import Event

from .db import Database


class JobWorker:
    """Single-consumer queue worker with a safe provider fallback."""

    def __init__(self, database: Database | None = None, poll_seconds: float = 1.0) -> None:
        self.database = database or Database()
        self.poll_seconds = poll_seconds

    def process_once(self) -> bool:
        job = self.database.claim_next_queued()
        if job is None:
            self.purge_once()
            return False

        # Provider integration is deliberately behind this boundary. Until a
        # real provider is configured, never fabricate a result image.
        self.database.transition_job(
            job["id"],
            "validating",
            progress_stage="validating",
        )
        self.database.transition_job(
            job["id"],
            "fallback",
            fail_reason="provider_not_ready",
            progress_stage=None,
        )
        self.database.schedule_input_purge(job["id"])
        self.purge_once()
        return True

    def purge_once(self) -> int:
        purged = 0
        for row in self.database.expired_files():
            path = Path(row["storage_path"])
            try:
                if path.exists() and path.is_file():
                    path.unlink()
                self.database.mark_file_purged(row["id"])
                purged += 1
            except OSError:
                # Keep the audit row pending so a later tick can retry safely.
                continue
        return purged

    def run_forever(self, stop_event: Event | None = None) -> None:
        stop_event = stop_event or Event()
        while not stop_event.is_set():
            self.process_once()
            stop_event.wait(self.poll_seconds)


if __name__ == "__main__":
    JobWorker().run_forever()
