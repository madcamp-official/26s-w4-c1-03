from __future__ import annotations

import io

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
    monkeypatch.setattr(storage, "SHOOT_DIR", tmp_path / "shoot")


def image_bytes() -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (10, 8), (120, 140, 120)).save(output, format="PNG")
    return output.getvalue()


def test_shoot_session_upload_download_and_claim() -> None:
    with TestClient(app) as client:
        created = client.post("/api/v1/shoot-sessions", headers={"X-Device-Id": "owner-device"}, json={"zoom": 2, "flash": "on", "internal": "never-public"})
        assert created.status_code == 201, created.text
        session = created.json()
        token = session["shareUrl"].rsplit("/", 1)[-1]
        assert client.get(f"/api/v1/shoot-upload/{token}/config").json()["policy"] == {"zoom": 2, "flash": "on"}

        uploaded = client.post(f"/api/v1/shoot-upload/{token}", files={"image": ("friend.png", image_bytes(), "image/png")})
        assert uploaded.status_code == 201, uploaded.text
        photo_id = uploaded.json()["photoId"]
        headers = {"X-Owner-Token": session["ownerToken"]}
        listed = client.get(f"/api/v1/shoot-sessions/{session['sessionId']}", headers=headers)
        assert [photo["photoId"] for photo in listed.json()["photos"]] == [photo_id]
        downloaded = client.get(f"/api/v1/shoot-sessions/{session['sessionId']}/photos/{photo_id}", headers=headers)
        assert downloaded.status_code == 200
        assert downloaded.headers["content-type"].startswith("image/png")
        assert client.post(f"/api/v1/shoot-sessions/{session['sessionId']}/claim", headers=headers).json() == {"claimed": True}
        assert client.get(f"/api/v1/shoot-sessions/{session['sessionId']}", headers=headers).status_code == 404


def test_shoot_token_rejects_tampering() -> None:
    with TestClient(app) as client:
        response = client.get("/api/v1/shoot-upload/not.a-token/config")
    assert response.status_code == 404
