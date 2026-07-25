from __future__ import annotations

import io
import json

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app import db, storage
from app.main import app


@pytest.fixture(autouse=True)
def isolated_runtime(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    monkeypatch.setattr(db, "DEFAULT_DB_PATH", tmp_path / "gamdo.sqlite3")
    monkeypatch.setattr(storage, "INPUT_DIR", tmp_path / "inputs")
    monkeypatch.setattr(storage, "RESULT_DIR", tmp_path / "results")
    monkeypatch.setattr(storage, "TMP_DIR", tmp_path / "tmp")


def image_bytes() -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (8, 8), (120, 140, 120)).save(output, format="PNG")
    return output.getvalue()


def test_health_and_presets() -> None:
    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "ok"}
        response = client.get("/api/v1/presets", headers={"X-Device-Id": "test-device"})
        assert response.status_code == 200
        assert len(response.json()) == 6
        assert response.headers["etag"]


def test_presets_have_six_complete_composition_and_color_profiles() -> None:
    with TestClient(app) as client:
        response = client.get("/api/v1/presets", headers={"X-Device-Id": "test-device"})
    assert response.status_code == 200
    for preset in response.json():
        assert {"subjectScaleRange", "subjectPosition", "headroomRange", "horizonPosition", "cameraPitchRange"} <= set(preset["composition"])
        assert {"exposureBias", "colorTemperature", "contrast", "saturation", "grain", "vignette", "fade"} <= set(preset["color"])


def test_missing_device_header_is_standardized() -> None:
    with TestClient(app) as client:
        response = client.get("/api/v1/presets")
        assert response.status_code == 400
        assert response.json()["code"] == "missing_device_id"


def test_edit_job_polling_does_not_force_fallback() -> None:
    headers = {"X-Device-Id": "test-device"}
    form = {
        "jobId": "job_test_001",
        "captureRef": "cap_test_001",
            "operations": json.dumps([{
                "type": "remove_objects",
                "masks": [{"rect": {"x": 0.1, "y": 0.1, "width": 0.1, "height": 0.1}}],
            }]),
        "styleParams": json.dumps({"v": 1, "color": {"exposureBias": 0.1}}),
        "resultCount": "2",
    }
    with TestClient(app) as client:
        created = client.post(
            "/api/v1/edit-jobs",
            headers=headers,
            data=form,
            files={"image": ("input.png", image_bytes(), "image/png")},
        )
        assert created.status_code == 202, created.text
        assert created.json() == {"jobId": "job_test_001", "status": "queued"}

        first = client.get("/api/v1/edit-jobs/job_test_001", headers=headers)
        assert first.status_code == 200
        assert first.json()["status"] == "queued"
        second = client.get("/api/v1/edit-jobs/job_test_001", headers=headers)
        assert second.status_code == 200
        assert second.json()["status"] == "queued"
        assert second.json()["results"] == []
        assert second.json()["failReason"] is None


def test_reference_analysis_is_synchronous_and_does_not_persist_upload() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/references/analyze",
            headers={"X-Device-Id": "test-device"},
            files={"image": ("reference.png", image_bytes(), "image/png")},
        )
        assert response.status_code == 200
        payload = response.json()
        assert payload["analysis"]["peopleCount"] == 0
        assert len(payload["analysis"]["palette"]) == 5
        assert len(payload["analysis"]["luminanceHistogram"]) == 16
        assert payload["targetComposition"]["targetAspectRatio"] in {"4:5", "1:1"}


def test_edit_job_rejects_large_edit_area() -> None:
    form = {
        "jobId": "job_area_limit",
        "captureRef": "cap_area_limit",
        "operations": json.dumps([{"type": "remove_objects", "maskAreaRatio": 0.31}]),
    }
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/edit-jobs",
            headers={"X-Device-Id": "area-device"},
            data=form,
            files={"image": ("input.png", image_bytes(), "image/png")},
        )
    assert response.status_code == 422
    assert response.json()["code"] == "edit_area_limit_exceeded"
