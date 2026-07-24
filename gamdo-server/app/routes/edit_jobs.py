from __future__ import annotations

import json
import re
from typing import Any

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile

from ..db import Database
from ..storage import save_exif_stripped_input
from .common import require_device_id


router = APIRouter()
JOB_ID_PATTERN = re.compile(r"^job_[A-Za-z0-9_-]{1,120}$")
ALLOWED_OPERATIONS = {
    "remove_objects",
    "simplify_background",
    "outpaint",
    "fill_rotation_gap",
    "relight",
    "eye_fix",
    "deblur_light",
    "skin_tone_even",
}


def parse_json_object(raw: str, field: str) -> dict[str, Any]:
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_json",
            "message": f"{field} must be valid JSON",
            "retryable": False,
        }) from exc
    if not isinstance(value, dict):
        raise HTTPException(status_code=422, detail={
            "code": "invalid_json_shape",
            "message": f"{field} must be an object",
            "retryable": False,
        })
    return value


def parse_operations(raw: str) -> list[dict[str, Any]]:
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_operations",
            "message": "operations must be a JSON array",
            "retryable": False,
        }) from exc
    if not isinstance(value, list) or not value:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_operations",
            "message": "operations must be a non-empty JSON array",
            "retryable": False,
        })
    for operation in value:
        if not isinstance(operation, dict) or operation.get("type") not in ALLOWED_OPERATIONS:
            raise HTTPException(status_code=422, detail={
                "code": "unsupported_operation",
                "message": "operation type is not allowed",
                "retryable": False,
            })
    return value


@router.post("/edit-jobs", status_code=202)
async def create_edit_job(
    device_id: str = Depends(require_device_id),
    job_id: str = Form(..., alias="jobId"),
    capture_ref: str = Form(..., alias="captureRef"),
    operations: str = Form(...),
    style_params: str = Form("{}", alias="styleParams"),
    result_count: int = Form(2, alias="resultCount"),
    image: UploadFile = File(...),
) -> dict[str, str]:
    if not JOB_ID_PATTERN.fullmatch(job_id):
        raise HTTPException(status_code=422, detail={
            "code": "invalid_job_id",
            "message": "jobId must start with job_ and contain safe characters",
            "retryable": False,
        })
    if not capture_ref or len(capture_ref) > 160:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_capture_ref",
            "message": "captureRef is required",
            "retryable": False,
        })
    if result_count < 1 or result_count > 2:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_result_count",
            "message": "resultCount must be between 1 and 2",
            "retryable": False,
        })
    parsed_operations = parse_operations(operations)
    parsed_style_params = parse_json_object(style_params, "styleParams")
    database = Database()
    existing = database.get_job(job_id)
    if existing is not None:
        raise HTTPException(status_code=409, detail={
            "code": "job_already_exists",
            "message": "jobId already exists",
            "retryable": False,
        })
    payload = await image.read()
    if not payload:
        raise HTTPException(status_code=422, detail={
            "code": "empty_image",
            "message": "image is required",
            "retryable": False,
        })
    try:
        storage_path, bytes_count = save_exif_stripped_input(job_id, payload)
    except Exception as exc:
        raise HTTPException(status_code=415, detail={
            "code": "unsupported_image",
            "message": "image could not be decoded",
            "retryable": False,
        }) from exc

    database.insert_job(
        job_id=job_id,
        device_uuid=device_id,
        capture_ref=capture_ref,
        operations=parsed_operations,
        style_params=parsed_style_params,
        result_count=result_count,
        input_file_id=f"jf_{job_id[4:]}",
        storage_path=storage_path,
        bytes_count=bytes_count,
    )
    return {"jobId": job_id, "status": "queued"}


@router.get("/edit-jobs/{job_id}")
def get_edit_job(job_id: str, _: str = Depends(require_device_id)) -> dict[str, Any]:
    if not JOB_ID_PATTERN.fullmatch(job_id):
        raise HTTPException(status_code=404, detail={
            "code": "job_not_found",
            "message": "job was not found",
            "retryable": False,
        })
    database = Database()
    job = database.get_job(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail={
            "code": "job_not_found",
            "message": "job was not found",
            "retryable": False,
        })
    if job["status"] == "queued":
        database.transition_job(job_id, "processing")
        job = database.get_job(job_id)
    elif job["status"] == "processing":
        database.transition_job(job_id, "fallback", fail_reason="provider_not_ready")
        database.schedule_input_purge(job_id)
        job = database.get_job(job_id)
    results = database.get_results(job_id)
    return {
        "jobId": job["id"],
        "status": job["status"],
        "progressStage": job["progress_stage"],
        "results": [
            {
                "url": file["storage_path"],
                "generative": bool(file["generative"]),
                "validation": file["validation_status"],
                "seed": file["seed"],
            }
            for file in results
        ],
        "failReason": job["fail_reason"],
    }
