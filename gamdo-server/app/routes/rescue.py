from __future__ import annotations

import io
import json

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from PIL import Image
from PIL.Image import DecompressionBombError, UnidentifiedImageError

from ..rescue_analysis import analyze_rescue
from .common import require_device_id


router = APIRouter()
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
ALLOWED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}


@router.post("/rescue/analyze")
async def analyze_rescue_route(
    _: str = Depends(require_device_id),
    image: UploadFile = File(...),
    capture_ref: str = Form("", alias="captureRef"),
    style_params: str = Form("{}", alias="styleParams"),
    reference_composition: str = Form("{}", alias="referenceComposition"),
) -> dict:
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
    try:
        with Image.open(io.BytesIO(payload)) as decoded:
            decoded.verify()
        parsed_style = json.loads(style_params)
        parsed_reference = json.loads(reference_composition)
        if not isinstance(parsed_style, dict) or not isinstance(parsed_reference, dict):
            raise ValueError("object expected")
        return analyze_rescue(payload, capture_ref, parsed_style, parsed_reference)
    except (DecompressionBombError, UnidentifiedImageError, OSError) as exc:
        raise HTTPException(status_code=415, detail={
            "code": "unsupported_image",
            "message": "image could not be decoded",
            "retryable": False,
        }) from exc
    except (ValueError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_rescue_parameters",
            "message": "styleParams and referenceComposition must be JSON objects",
            "retryable": False,
        }) from exc
    except Exception as exc:
        raise HTTPException(status_code=503, detail={
            "code": "rescue_analysis_unavailable",
            "message": "rescue analysis is temporarily unavailable",
            "retryable": True,
        }) from exc
