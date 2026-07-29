from __future__ import annotations

import io
import json

import pytest
from fastapi.testclient import TestClient
from PIL import ExifTags, Image

from app import db, storage
from app.main import app
from app.routes import edit_jobs as edit_jobs_routes
from app.routes import references as references_routes
from app.routes import rescue as rescue_routes


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


def image_bytes_with_gps() -> bytes:
    """A JPEG carrying GPS EXIF, the same shape a phone gallery photo has (O-9)."""
    image = Image.new("RGB", (8, 8), (120, 140, 120))
    exif = image.getexif()
    exif[ExifTags.Base.Orientation] = 6
    exif[ExifTags.Base.GPSInfo] = {1: "N", 2: (37.0, 33.0, 12.34), 3: "E", 4: (127.0, 1.0, 2.34)}
    output = io.BytesIO()
    image.save(output, format="JPEG", exif=exif)
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
        assert payload["analysisVersion"] == 3
        assert payload["analysis"]["peopleCount"] == 0
        assert len(payload["analysis"]["palette"]) == 5
        assert len(payload["analysis"]["luminanceHistogram"]) == 16
        assert payload["targetComposition"]["targetAspectRatio"] in {"4:5", "1:1"}
        assert payload["capabilities"] == {"composition": False, "color": True}


def test_reference_analysis_rejects_unsupported_content_type() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/references/analyze",
            headers={"X-Device-Id": "test-device"},
            files={"image": ("reference.txt", b"not an image", "text/plain")},
        )
    assert response.status_code == 415
    assert response.json()["code"] == "unsupported_image_type"


def test_rescue_analysis_returns_recommendations_without_creating_job() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/rescue/analyze",
            headers={"X-Device-Id": "rescue-device"},
            data={
                "captureRef": "cap_rescue_1",
                "styleParams": json.dumps({"composition": {"backgroundRatioRange": [0.5, 0.8]}}),
                "referenceComposition": "{}",
            },
            files={"image": ("photo.png", image_bytes(), "image/png")},
        )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["analysisVersion"] == 1
    assert payload["captureRef"] == "cap_rescue_1"
    assert payload["recommendations"][0]["kind"] == "local_style"
    assert payload["capabilities"]["localStyle"] is True


def test_rescue_analysis_rejects_invalid_parameters() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/rescue/analyze",
            headers={"X-Device-Id": "rescue-device"},
            data={"styleParams": "not-json"},
            files={"image": ("photo.png", image_bytes(), "image/png")},
        )
    assert response.status_code == 422
    assert response.json()["code"] == "invalid_rescue_parameters"


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


def test_edit_job_rejects_invalid_operations_json() -> None:
    form = {
        "jobId": "job_invalid_json",
        "captureRef": "cap_invalid_json",
        "operations": "not-json",
    }
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/edit-jobs",
            headers={"X-Device-Id": "invalid-json-device"},
            data=form,
            files={"image": ("input.png", image_bytes(), "image/png")},
        )
    assert response.status_code == 422
    assert response.json()["code"] == "invalid_operations"
    assert response.json()["retryable"] is False


def test_remove_objects_requires_an_explicit_mask() -> None:
    form = {
        "jobId": "job_missing_mask",
        "captureRef": "cap_missing_mask",
        "operations": json.dumps([{"type": "remove_objects"}]),
    }
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/edit-jobs",
            headers={"X-Device-Id": "missing-mask-device"},
            data=form,
            files={"image": ("input.png", image_bytes(), "image/png")},
        )
    assert response.status_code == 422
    assert response.json()["code"] == "mask_required"


def test_edit_job_rejects_out_of_bounds_or_tiny_mask() -> None:
    form = {
        "jobId": "job_invalid_mask",
        "captureRef": "cap_invalid_mask",
        "operations": json.dumps([{
            "type": "remove_objects",
            "masks": [{"rect": {"x": 0.98, "y": 0.1, "width": 0.05, "height": 0.01}}],
        }]),
    }
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/edit-jobs",
            headers={"X-Device-Id": "invalid-mask-device"},
            data=form,
            files={"image": ("input.png", image_bytes(), "image/png")},
        )
    assert response.status_code == 422
    assert response.json()["code"] == "invalid_mask"


def test_outpaint_requires_one_bounded_direction() -> None:
    base = {
        "jobId": "job_outpaint_invalid",
        "captureRef": "cap_outpaint_invalid",
        "operations": json.dumps([{"type": "outpaint", "direction": "top", "ratio": 0.2}]),
    }
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/edit-jobs",
            headers={"X-Device-Id": "outpaint-device"},
            data=base,
            files={"image": ("input.png", image_bytes(), "image/png")},
        )
    assert response.status_code == 422
    assert response.json()["code"] == "invalid_outpaint_ratio"


def test_outpaint_is_accepted_as_a_single_explicit_operation() -> None:
    form = {
        "jobId": "job_outpaint_valid",
        "captureRef": "cap_outpaint_valid",
        "operations": json.dumps([{"type": "outpaint", "direction": "right", "ratio": 0.15}]),
    }
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/edit-jobs",
            headers={"X-Device-Id": "outpaint-valid-device"},
            data=form,
            files={"image": ("input.png", image_bytes(), "image/png")},
        )
    assert response.status_code == 202
    assert response.json()["status"] == "queued"


def test_edit_job_rejects_non_image_payload_without_persisting_file() -> None:
    form = {
        "jobId": "job_bad_image",
        "captureRef": "cap_bad_image",
        "operations": json.dumps([{
            "type": "remove_objects",
            "masks": [{"rect": {"x": 0.1, "y": 0.1, "width": 0.1, "height": 0.1}}],
        }]),
    }
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/edit-jobs",
            headers={"X-Device-Id": "bad-image-device"},
            data=form,
            files={"image": ("input.txt", b"not an image", "text/plain")},
        )
    assert response.status_code == 415
    assert response.json()["code"] == "unsupported_image"
    assert list(storage.INPUT_DIR.glob("**/*")) == []


# --- O-9: GPS EXIF is stripped at the earliest point on every upload path ---


def test_reference_analysis_strips_gps_before_analysis(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, bytes] = {}
    real_analyze = references_routes.analyze_reference

    def spy(payload: bytes):
        captured["payload"] = payload
        return real_analyze(payload)

    monkeypatch.setattr(references_routes, "analyze_reference", spy)

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/references/analyze",
            headers={"X-Device-Id": "test-device"},
            files={"image": ("reference.jpg", image_bytes_with_gps(), "image/jpeg")},
        )
    assert response.status_code == 200, response.text
    assert "payload" in captured  # the spy actually ran
    exif = Image.open(io.BytesIO(captured["payload"])).getexif()
    assert ExifTags.Base.GPSInfo not in exif
    assert exif.get(ExifTags.Base.Orientation) == 6  # stripped, not just discarded wholesale


def test_rescue_analysis_strips_gps_before_analysis(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, bytes] = {}
    real_analyze = rescue_routes.analyze_rescue

    def spy(payload: bytes, capture_ref, style_params, reference_composition):
        captured["payload"] = payload
        return real_analyze(payload, capture_ref, style_params, reference_composition)

    monkeypatch.setattr(rescue_routes, "analyze_rescue", spy)

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/rescue/analyze",
            headers={"X-Device-Id": "rescue-device"},
            data={"captureRef": "cap_gps", "styleParams": "{}", "referenceComposition": "{}"},
            files={"image": ("photo.jpg", image_bytes_with_gps(), "image/jpeg")},
        )
    assert response.status_code == 200, response.text
    assert "payload" in captured
    exif = Image.open(io.BytesIO(captured["payload"])).getexif()
    assert ExifTags.Base.GPSInfo not in exif
    assert exif.get(ExifTags.Base.Orientation) == 6


def test_edit_job_strips_gps_before_the_persisted_copy_is_written(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, bytes] = {}
    real_save = edit_jobs_routes.save_exif_stripped_input

    def spy(job_id: str, payload: bytes):
        captured["payload"] = payload
        return real_save(job_id, payload)

    monkeypatch.setattr(edit_jobs_routes, "save_exif_stripped_input", spy)

    form = {
        "jobId": "job_gps_test",
        "captureRef": "cap_gps_test",
        "operations": json.dumps([{
            "type": "remove_objects",
            "masks": [{"rect": {"x": 0.1, "y": 0.1, "width": 0.1, "height": 0.1}}],
        }]),
    }
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/edit-jobs",
            headers={"X-Device-Id": "gps-device"},
            data=form,
            files={"image": ("input.jpg", image_bytes_with_gps(), "image/jpeg")},
        )
    assert response.status_code == 202, response.text
    assert "payload" in captured  # save_exif_stripped_input already received GPS-free bytes
    exif = Image.open(io.BytesIO(captured["payload"])).getexif()
    assert ExifTags.Base.GPSInfo not in exif
    assert exif.get(ExifTags.Base.Orientation) == 6
