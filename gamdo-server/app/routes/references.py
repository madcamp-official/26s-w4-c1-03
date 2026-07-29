from __future__ import annotations

import io

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from PIL import Image
from PIL.Image import DecompressionBombError, UnidentifiedImageError

from ..reference_analysis import analyze_reference
from ..storage import strip_gps_exif
from .common import require_device_id


router = APIRouter()
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
ALLOWED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}


@router.post("/references/analyze", dependencies=[Depends(require_device_id)])
async def analyze_reference_route(image: UploadFile = File(...)) -> dict:
    if image.content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(status_code=415, detail={
            "code": "unsupported_image_type",
            "message": "JPEG, PNG, or WebP image is required",
            "retryable": False,
        })
    payload = await image.read(MAX_UPLOAD_BYTES + 1)
    if len(payload) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail={
            "code": "image_too_large",
            "message": "image exceeds the 20MB limit",
            "retryable": False,
        })
    if not payload:
        raise HTTPException(status_code=422, detail={
            "code": "empty_image",
            "message": "image is required",
            "retryable": False,
        })
    # Earliest point on this path (O-9): strip GPS before the image is even
    # decoded for validation, let alone analyzed.
    payload = strip_gps_exif(payload)
    try:
        with Image.open(io.BytesIO(payload)) as decoded:
            decoded.verify()
    except (DecompressionBombError, UnidentifiedImageError, OSError) as exc:
        raise HTTPException(status_code=415, detail={
            "code": "unsupported_image",
            "message": "image could not be decoded",
            "retryable": False,
        }) from exc
    try:
        return analyze_reference(payload)
    except DecompressionBombError as exc:
        raise HTTPException(status_code=413, detail={
            "code": "image_dimensions_exceeded",
            "message": "image dimensions exceed the supported limit",
            "retryable": False,
        }) from exc
    except Exception as exc:
        raise HTTPException(status_code=503, detail={
            "code": "reference_analysis_unavailable",
            "message": "reference analysis is temporarily unavailable",
            "retryable": True,
        }) from exc
