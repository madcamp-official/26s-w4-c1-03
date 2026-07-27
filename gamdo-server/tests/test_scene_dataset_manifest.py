import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts" / "validate_scene_dataset.py"
EXAMPLE = ROOT / "datasets" / "scene" / "manifest.example.jsonl"


def test_example_manifest_is_valid():
    result = subprocess.run(
        [sys.executable, str(SCRIPT), str(EXAMPLE), "--require-mask"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr


def test_validator_rejects_missing_license_and_bad_geometry(tmp_path):
    manifest = tmp_path / "bad.jsonl"
    manifest.write_text(
        json.dumps(
            {
                "image": "x.jpg",
                "source": "unknown",
                "license": "",
                "license_url": "",
                "commercial_use": False,
                "split": "train",
                "width": 100,
                "height": 100,
                "instances": [
                    {"category": "bag", "bbox": [90, 90, 20, 20], "polygon": [[0, 0], [1, 1]]}
                ],
            }
        )
        + "\n",
        encoding="utf-8",
    )
    result = subprocess.run(
        [sys.executable, str(SCRIPT), str(manifest), "--require-mask"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 1
    assert "license is required" in result.stderr
    assert "invalid bbox" in result.stderr
    assert "requires a valid polygon" in result.stderr
