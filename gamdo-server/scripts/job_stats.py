from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path


def stats(database: Path) -> dict[str, object]:
    with sqlite3.connect(database) as connection:
        statuses = dict(connection.execute("SELECT status, COUNT(*) FROM edit_jobs GROUP BY status"))
        durations = connection.execute(
            "SELECT finished_at - started_at FROM edit_jobs WHERE finished_at IS NOT NULL AND started_at IS NOT NULL"
        ).fetchall()
        purge = connection.execute(
            "SELECT COUNT(*) FROM edit_job_files WHERE purge_after IS NOT NULL AND purged_at IS NULL"
        ).fetchone()[0]
    values = [row[0] for row in durations if row[0] is not None]
    return {
        "jobsByStatus": statuses,
        "completedJobs": len(values),
        "durationMs": {"min": min(values) if values else None, "max": max(values) if values else None},
        "pendingPurgeFiles": purge,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("database", type=Path)
    args = parser.parse_args()
    print(json.dumps(stats(args.database), ensure_ascii=False, indent=2))
