from __future__ import annotations

import base64
import hashlib
import hmac
import io
import json
import os
import secrets
import time
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from PIL import Image, UnidentifiedImageError

from ..db import Database, SERVER_ROOT, now_ms
from ..storage import save_shoot_photo, strip_gps_exif
from .common import require_device_id


router = APIRouter()
SESSION_TTL_MS = 60 * 60 * 1000
MAX_PHOTOS = 5
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
ALLOWED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}
TOKEN_SECRET = os.getenv("GAMDO_SHOOT_TOKEN_SECRET", "gamdo-demo-shoot-token").encode()


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def _unb64(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def make_share_token(session_id: str, expires_at: int) -> str:
    payload = _b64(json.dumps({"sid": session_id, "exp": expires_at}, separators=(",", ":")).encode())
    signature = _b64(hmac.new(TOKEN_SECRET, payload.encode(), hashlib.sha256).digest())
    return f"{payload}.{signature}"


def parse_share_token(token: str) -> tuple[str, int]:
    try:
        payload, signature = token.split(".", 1)
        expected = _b64(hmac.new(TOKEN_SECRET, payload.encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(signature, expected):
            raise ValueError("signature")
        decoded = json.loads(_unb64(payload))
        session_id, expires_at = decoded["sid"], int(decoded["exp"])
        if not session_id.startswith("shoot_") or expires_at <= now_ms():
            raise ValueError("expired")
        return session_id, expires_at
    except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=404, detail={
            "code": "shoot_session_unavailable", "message": "shooting link is unavailable", "retryable": False,
        }) from exc


def _owner_token(x_owner_token: str | None = Header(default=None, alias="X-Owner-Token")) -> str:
    if not x_owner_token or len(x_owner_token) > 256:
        raise HTTPException(status_code=401, detail={
            "code": "owner_token_required", "message": "owner token is required", "retryable": False,
        })
    return x_owner_token


def _public_policy(policy: dict[str, Any]) -> dict[str, Any]:
    """Only send the delegated shooting rules, never the complete local profile."""
    allowed = {"zoom", "flash", "subjectAnchor", "subjectScale", "layout", "title"}
    return {key: value for key, value in policy.items() if key in allowed}


def _remove_paths(rows: list[Any]) -> None:
    for row in rows:
        path = Path(row["storage_path"])
        if not path.is_absolute():
            path = SERVER_ROOT / path
        path.unlink(missing_ok=True)


def purge_expired_sessions(database: Database) -> None:
    for session in database.expired_shoot_sessions():
        _remove_paths(database.delete_shoot_session(session["id"]))


def _require_owner(database: Database, session_id: str, token: str):
    purge_expired_sessions(database)
    session = database.get_shoot_session(session_id)
    if session is None or not hmac.compare_digest(session["owner_token"], token):
        raise HTTPException(status_code=404, detail={
            "code": "shoot_session_unavailable", "message": "shooting session is unavailable", "retryable": False,
        })
    return session


@router.post("/shoot-sessions", status_code=201)
def create_shoot_session(
    policy: dict[str, Any],
    _: str = Depends(require_device_id),
) -> dict[str, Any]:
    database = Database()
    purge_expired_sessions(database)
    session_id = f"shoot_{secrets.token_urlsafe(12)}"
    owner_token = secrets.token_urlsafe(32)
    expires_at = now_ms() + SESSION_TTL_MS
    database.create_shoot_session(
        session_id=session_id,
        owner_token=owner_token,
        policy=_public_policy(policy),
        expires_at=expires_at,
        max_photos=MAX_PHOTOS,
    )
    token = make_share_token(session_id, expires_at)
    return {
        "sessionId": session_id,
        "ownerToken": owner_token,
        "shareUrl": f"/shoot/{token}",
        "expiresAt": expires_at,
        "maxPhotos": MAX_PHOTOS,
    }


@router.get("/shoot-sessions/{session_id}")
def get_shoot_session(session_id: str, owner_token: str = Depends(_owner_token)) -> dict[str, Any]:
    database = Database()
    session = _require_owner(database, session_id, owner_token)
    photos = database.list_shoot_photos(session_id)
    return {
        "sessionId": session_id,
        "expiresAt": session["expires_at"],
        "maxPhotos": session["max_photos"],
        "photos": [{"photoId": photo["id"], "createdAt": photo["created_at"]} for photo in photos],
    }


@router.get("/shoot-sessions/{session_id}/photos/{photo_id}")
def download_shoot_photo(session_id: str, photo_id: str, owner_token: str = Depends(_owner_token)) -> FileResponse:
    database = Database()
    _require_owner(database, session_id, owner_token)
    photo = database.get_shoot_photo(session_id, photo_id)
    if photo is None:
        raise HTTPException(status_code=404, detail={"code": "photo_not_found", "message": "photo was not found", "retryable": False})
    path = Path(photo["storage_path"])
    if not path.is_absolute():
        path = SERVER_ROOT / path
    if not path.exists():
        raise HTTPException(status_code=404, detail={"code": "photo_expired", "message": "photo is unavailable", "retryable": False})
    return FileResponse(path, media_type="image/png", filename=f"{photo_id}.png")


@router.post("/shoot-sessions/{session_id}/claim")
def claim_shoot_session(session_id: str, owner_token: str = Depends(_owner_token)) -> dict[str, bool]:
    database = Database()
    _require_owner(database, session_id, owner_token)
    _remove_paths(database.delete_shoot_session(session_id))
    return {"claimed": True}


@router.get("/shoot-upload/{share_token}/config")
def shoot_config(share_token: str) -> dict[str, Any]:
    session_id, _ = parse_share_token(share_token)
    database = Database()
    purge_expired_sessions(database)
    session = database.get_shoot_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail={"code": "shoot_session_unavailable", "message": "shooting link is unavailable", "retryable": False})
    return {"maxPhotos": session["max_photos"], "policy": json.loads(session["policy_json"])}


@router.post("/shoot-upload/{share_token}", status_code=201)
async def upload_shoot_photo(share_token: str, image: UploadFile = File(...), shot_note: str = Form("")) -> dict[str, Any]:
    del shot_note  # reserved for a future web-only caption; never persisted in this MVP.
    if image.content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(status_code=415, detail={"code": "unsupported_image_type", "message": "JPEG, PNG, or WebP image is required", "retryable": False})
    session_id, _ = parse_share_token(share_token)
    database = Database()
    purge_expired_sessions(database)
    session = database.get_shoot_session(session_id)
    if session is None or len(database.list_shoot_photos(session_id)) >= session["max_photos"]:
        raise HTTPException(status_code=409, detail={"code": "shoot_session_full", "message": "shooting session cannot accept more photos", "retryable": False})
    payload = strip_gps_exif(await image.read(MAX_UPLOAD_BYTES + 1))
    if len(payload) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail={"code": "image_too_large", "message": "image exceeds the 20MB limit", "retryable": False})
    try:
        with Image.open(io.BytesIO(payload)) as decoded:
            decoded.verify()
    except (UnidentifiedImageError, OSError) as exc:
        raise HTTPException(status_code=415, detail={"code": "unsupported_image", "message": "image could not be decoded", "retryable": False}) from exc
    photo_id = f"shot_{secrets.token_urlsafe(12)}"
    storage_path, bytes_count = save_shoot_photo(photo_id, payload)
    database.add_shoot_photo(photo_id=photo_id, session_id=session_id, storage_path=storage_path, bytes_count=bytes_count)
    return {"photoId": photo_id}
