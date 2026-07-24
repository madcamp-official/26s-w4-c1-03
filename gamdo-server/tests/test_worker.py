from __future__ import annotations

from pathlib import Path

from app.db import Database
from app.worker import JobWorker


def seed_job(database: Database, path: Path) -> None:
    path.write_bytes(b"temporary input")
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
