from __future__ import annotations

import sqlite3
from pathlib import Path

from scripts.local_metrics import metrics


def test_local_metrics_calculates_capture_save_feedback_and_guide_rates(tmp_path: Path) -> None:
    database = tmp_path / "gamdo.db"
    with sqlite3.connect(database) as connection:
        connection.executescript(
            """
            CREATE TABLE sessions (id TEXT PRIMARY KEY);
            CREATE TABLE captures (id TEXT PRIMARY KEY, source TEXT, deleted_at INTEGER, saved_to_gallery INTEGER);
            CREATE TABLE feedback (id TEXT PRIMARY KEY, capture_id TEXT);
            CREATE TABLE session_guides (id TEXT PRIMARY KEY, session_id TEXT, resolved INTEGER);
            INSERT INTO sessions VALUES ('ses_1');
            INSERT INTO sessions VALUES ('ses_2');
            INSERT INTO captures VALUES ('cap_1', 'camera_manual', NULL, 1);
            INSERT INTO captures VALUES ('cap_2', 'camera_manual', NULL, 0);
            INSERT INTO feedback VALUES ('fb_1', 'cap_1');
            INSERT INTO session_guides VALUES ('guide_1', 'ses_1', 1);
            INSERT INTO session_guides VALUES ('guide_2', 'ses_1', NULL);
            """
        )
    result = metrics(database)
    assert result["firstCaptureCompletionRate"] == 1.0
    assert result["saveRate"] == 0.5
    assert result["feedbackRate"] == 0.5
    assert result["overlayResolvedGuideRate"] == 0.5
