from __future__ import annotations

import json
import os
from pathlib import Path
from threading import Event

from .db import Database
from .comfyui_provider import provider_from_environment
from .generative import (
    CandidateValidator,
    GenerativeEditProvider,
    InsightFaceVerifier,
    ProviderNotReady,
    UnavailableProvider,
    candidate_id,
)


class JobWorker:
    """Single-consumer queue worker with a safe provider fallback."""

    def __init__(
        self,
        database: Database | None = None,
        poll_seconds: float = 1.0,
        provider: GenerativeEditProvider | None = None,
        validator: CandidateValidator | None = None,
    ) -> None:
        self.database = database or Database()
        self.poll_seconds = poll_seconds
        self.provider = provider or provider_from_environment()
        self.validator = validator or CandidateValidator(InsightFaceVerifier.from_environment())
        self.processing_timeout_ms = int(os.getenv("GAMDO_PROCESSING_TIMEOUT_MS", "300000"))

    def process_once(self) -> bool:
        self.database.recover_stale_jobs(self.processing_timeout_ms)
        job = self.database.claim_next_queued()
        if job is None:
            self.purge_once()
            return False

        operations = json.loads(job["operations_json"])
        input_row = self.database.get_input_file(job["id"])
        try:
            if input_row is None or not self.validator.mask_is_safe(
                Path(input_row["storage_path"]), operations
            ):
                self.database.transition_job(job["id"], "fallback", fail_reason="face_mask_protected")
                self.database.schedule_input_purge(job["id"])
                self.purge_once()
                return True
            candidates = self.provider.remove_objects(
                Path(input_row["storage_path"]), operations, job["result_count"]
            )
        except (ProviderNotReady, OSError, TimeoutError, ValueError):
            self.database.transition_job(job["id"], "fallback", fail_reason="provider_not_ready")
            self.database.schedule_input_purge(job["id"])
            self.purge_once()
            return True

        self.database.transition_job(job["id"], "validating", progress_stage="validating")
        passed = 0
        for candidate in candidates[: job["result_count"]]:
            validation = self.validator.validate(Path(input_row["storage_path"]), candidate)
            if not validation.passed:
                continue
            self.database.insert_result(
                file_id=candidate_id(job["id"], candidate),
                job_id=job["id"],
                storage_path=str(candidate.path),
                bytes_count=candidate.path.stat().st_size,
                seed=candidate.seed,
                validation_json=validation.validation,
            )
            passed += 1
        if passed:
            self.database.transition_job(job["id"], "done", progress_stage=None)
        else:
            self.database.transition_job(job["id"], "fallback", fail_reason="candidate_validation_failed")
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
        return purged + self.database.purge_old_job_metadata()

    def run_forever(self, stop_event: Event | None = None) -> None:
        stop_event = stop_event or Event()
        while not stop_event.is_set():
            self.process_once()
            stop_event.wait(self.poll_seconds)


if __name__ == "__main__":
    JobWorker().run_forever()
