from __future__ import annotations

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile

from ..reference_analysis import analyze_reference
from .common import require_device_id


router = APIRouter()


@router.post("/references/analyze", dependencies=[Depends(require_device_id)])
async def analyze_reference_route(image: UploadFile = File(...)) -> dict:
    payload = await image.read()
    if not payload:
        raise HTTPException(status_code=422, detail={
            "code": "empty_image",
            "message": "image is required",
            "retryable": False,
        })
    try:
        return analyze_reference(payload)
    except Exception as exc:
        raise HTTPException(status_code=415, detail={
            "code": "unsupported_image",
            "message": "image could not be decoded",
            "retryable": False,
        }) from exc
