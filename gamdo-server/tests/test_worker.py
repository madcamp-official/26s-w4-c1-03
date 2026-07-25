from __future__ import annotations

from pathlib import Path

from PIL import Image

from app.db import Database
from app.generative import CandidateValidator, GeneratedCandidate
from app.worker import JobWorker


def seed_job(database: Database, path: Path) -> None:
    Image.new("RGB", (16, 16), (120, 140, 120)).save(path)
    database.insert_job(
        job_id="job_worker_001",
        device_uuid="device_worker",
        capture_ref="cap_worker",
        operations=[{"type": "remove_objects", "auto": True}],
        style_params={"v": 1},
        result_count=2,
        input_file_id="jf_worker_001",
        storage_path=str(path),
        bytes_count=path.stat().st_size,
    )


def test_worker_claims_job_falls_back_and_purges_input(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("app.db.DEFAULT_DB_PATH", tmp_path / "gamdo.sqlite3")
    database = Database()
    database.initialize()
    input_path = tmp_path / "inputs" / "job_worker_001.png"
    input_path.parent.mkdir()
    seed_job(database, input_path)

    assert JobWorker(database).process_once() is True

    job = database.get_job("job_worker_001")
    assert job is not None
    assert job["status"] == "fallback"
    assert job["fail_reason"] == "provider_not_ready"
    assert not input_path.exists()
    assert database.expired_files() == []


def test_worker_idle_tick_only_runs_purge(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("app.db.DEFAULT_DB_PATH", tmp_path / "gamdo.sqlite3")
    database = Database()
    database.initialize()

    assert JobWorker(database).process_once() is False


class CopyProvider:
    def __init__(self, output: Path) -> None:
        self.output = output

    def remove_objects(self, image_path, operations, result_count):
        self.output.write_bytes(image_path.read_bytes())
        return [GeneratedCandidate(self.output, 42)]


class RejectingMaskValidator:
    def mask_is_safe(self, original_path, operations):
        return False


class CountingProvider(CopyProvider):
    def __init__(self, output: Path) -> None:
        super().__init__(output)
        self.calls = 0

    def remove_objects(self, image_path, operations, result_count):
        self.calls += 1
        return super().remove_objects(image_path, operations, result_count)


class SameIdentity:
    def verify(self, original, candidate):
        return True


def test_worker_done_requires_provider_and_identity_validation(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("app.db.DEFAULT_DB_PATH", tmp_path / "gamdo.sqlite3")
    database = Database()
    database.initialize()
    input_path = tmp_path / "inputs" / "job_worker_001.png"
    input_path.parent.mkdir()
    seed_job(database, input_path)
    output_path = tmp_path / "results" / "candidate.png"
    output_path.parent.mkdir()

    worker = JobWorker(
        database,
        provider=CopyProvider(output_path),
        validator=CandidateValidator(SameIdentity()),
    )
    assert worker.process_once() is True

    job = database.get_job("job_worker_001")
    assert job is not None
    assert job["status"] == "done"
    assert len(database.get_results("job_worker_001")) == 1


def test_worker_falls_back_before_provider_when_mask_touches_face(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("app.db.DEFAULT_DB_PATH", tmp_path / "gamdo.sqlite3")
    database = Database()
    database.initialize()
    input_path = tmp_path / "inputs" / "job_worker_001.png"
    input_path.parent.mkdir()
    seed_job(database, input_path)
    output_path = tmp_path / "results" / "candidate.png"
    output_path.parent.mkdir()
    provider = CountingProvider(output_path)

    worker = JobWorker(database, provider=provider, validator=RejectingMaskValidator())
    assert worker.process_once() is True

    job = database.get_job("job_worker_001")
    assert job is not None
    assert job["status"] == "fallback"
    assert job["fail_reason"] == "face_mask_protected"
    assert provider.calls == 0
    assert not input_path.exists()


def test_result_delivery_schedules_24_hour_purge(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("app.db.DEFAULT_DB_PATH", tmp_path / "gamdo.sqlite3")
    database = Database()
    database.initialize()
    input_path = tmp_path / "inputs" / "job_worker_001.png"
    input_path.parent.mkdir()
    seed_job(database, input_path)
    output_path = tmp_path / "results" / "candidate.png"
    output_path.parent.mkdir()
    worker = JobWorker(database, provider=CopyProvider(output_path), validator=CandidateValidator(SameIdentity()))
    assert worker.process_once() is True
    database.mark_results_delivered("job_worker_001")
    result = database.get_results("job_worker_001")[0]
    assert result["delivered_at"] is not None
    assert result["purge_after"] - result["delivered_at"] == 24 * 60 * 60 * 1000
    with database.connect() as connection:
        connection.execute("UPDATE edit_job_files SET purge_after = 1 WHERE id = ?", (result["id"],))
    assert worker.purge_once() == 1
    assert not output_path.exists()
    assert database.get_results("job_worker_001")[0]["purged_at"] is not None


def test_stale_processing_job_is_failed_by_worker(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("app.db.DEFAULT_DB_PATH", tmp_path / "gamdo.sqlite3")
    database = Database()
    database.initialize()
    input_path = tmp_path / "inputs" / "job_worker_001.png"
    input_path.parent.mkdir()
    seed_job(database, input_path)
    assert database.claim_next_queued() is not None
    with database.connect() as connection:
        connection.execute("UPDATE edit_jobs SET updated_at = 1 WHERE id = ?", ("job_worker_001",))
    worker = JobWorker(database)
    assert worker.process_once() is False
    job = database.get_job("job_worker_001")
    assert job["status"] == "failed"
    assert job["fail_reason"] == "processing_timeout"


def test_old_terminal_job_metadata_is_purged_after_seven_days(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("app.db.DEFAULT_DB_PATH", tmp_path / "gamdo.sqlite3")
    database = Database()
    database.initialize()
    input_path = tmp_path / "inputs" / "job_worker_001.png"
    input_path.parent.mkdir()
    seed_job(database, input_path)
    database.transition_job("job_worker_001", "fallback", fail_reason="provider_not_ready")
    with database.connect() as connection:
        connection.execute("UPDATE edit_jobs SET finished_at = 1, updated_at = 1 WHERE id = ?", ("job_worker_001",))
    assert JobWorker(database).purge_once() == 1
    assert database.get_job("job_worker_001") is None
