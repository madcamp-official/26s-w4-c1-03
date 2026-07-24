from __future__ import annotations

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile

from .common import require_device_id


router = APIRouter()


@router.post("/references/analyze", dependencies=[Depends(require_device_id)])
async def analyze_reference(image: UploadFile = File(...)) -> None:
    # Day 1 only establishes the route. The file is read and discarded; it is
    # intentionally not sent to the job database or persistent storage.
    await image.read()
    raise HTTPException(status_code=501, detail={
        "code": "reference_analysis_not_ready",
        "message": "Reference analysis is not available yet",
        "retryable": True,
    })
