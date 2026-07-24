from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path


def _count(connection: sqlite3.Connection, query: str) -> int:
    return int(connection.execute(query).fetchone()[0])


def metrics(database: Path) -> dict[str, float | int]:
    with sqlite3.connect(database) as connection:
        sessions = _count(connection, "SELECT COUNT(*) FROM sessions")
        captures = _count(connection, "SELECT COUNT(*) FROM captures WHERE source = 'camera_manual' AND deleted_at IS NULL")
        saved = _count(connection, "SELECT COUNT(*) FROM captures WHERE saved_to_gallery = 1 AND deleted_at IS NULL")
        feedback = _count(connection, "SELECT COUNT(*) FROM feedback")
        overlay_resolved = _count(connection, "SELECT COUNT(*) FROM session_guides WHERE resolved = 1")
        guide_events = _count(connection, "SELECT COUNT(*) FROM session_guides")
    return {
        "sessions": sessions,
        "firstCaptureCompletionRate": round(captures / sessions, 4) if sessions else 0.0,
        "saveRate": round(saved / captures, 4) if captures else 0.0,
        "feedbackRate": round(feedback / captures, 4) if captures else 0.0,
        "overlayResolvedGuideRate": round(overlay_resolved / guide_events, 4) if guide_events else 0.0,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Summarize local GAMDO Room KPI tables")
    parser.add_argument("database", type=Path)
    args = parser.parse_args()
    print(json.dumps(metrics(args.database), ensure_ascii=False, indent=2))
