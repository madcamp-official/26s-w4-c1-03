from __future__ import annotations

import json
import io
import os
import re
import time
import math
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from PIL import Image

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
MAX_UPLOAD_BYTES = int(os.getenv("GAMDO_MAX_UPLOAD_BYTES", str(12 * 1024 * 1024)))
MAX_IMAGE_DIMENSION = int(os.getenv("GAMDO_MAX_IMAGE_DIMENSION", "4096"))
MAX_EDIT_AREA_RATIO = float(os.getenv("GAMDO_MAX_EDIT_AREA_RATIO", "0.30"))
MAX_JOBS_PER_HOUR = int(os.getenv("GAMDO_MAX_JOBS_PER_HOUR", "10"))
MAX_ACTIVE_JOBS = int(os.getenv("GAMDO_MAX_ACTIVE_JOBS", "1"))
MAX_MASK_COUNT = int(os.getenv("GAMDO_MAX_MASK_COUNT", "8"))
MIN_MASK_DIMENSION_RATIO = float(os.getenv("GAMDO_MIN_MASK_DIMENSION_RATIO", "0.01"))


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
        area = operation.get("maskAreaRatio")
        if area is not None and (not isinstance(area, (int, float)) or not 0 <= area <= MAX_EDIT_AREA_RATIO):
            raise HTTPException(status_code=422, detail={
                "code": "edit_area_limit_exceeded",
                "message": f"maskAreaRatio must be between 0 and {MAX_EDIT_AREA_RATIO}",
                "retryable": False,
            })
        if operation.get("type") == "remove_objects":
            masks = operation.get("masks")
            if not isinstance(masks, list) or not masks:
                raise HTTPException(status_code=422, detail={
                    "code": "mask_required",
                    "message": "remove_objects requires at least one explicit mask",
                    "retryable": False,
                })
            if len(masks) > MAX_MASK_COUNT:
                raise HTTPException(status_code=422, detail={
                    "code": "mask_count_exceeded",
                    "message": f"remove_objects supports at most {MAX_MASK_COUNT} masks",
                    "retryable": False,
                })
            measured_area = 0.0
            for mask in masks:
                measured_area += _validate_mask(mask)
            if measured_area > MAX_EDIT_AREA_RATIO:
                raise HTTPException(status_code=422, detail={
                    "code": "edit_area_limit_exceeded",
                    "message": f"mask area must be at most {MAX_EDIT_AREA_RATIO}",
                    "retryable": False,
                })
            # Keep the server-side measurement authoritative even when a client
            # omits or misreports the convenience field.
            operation["maskAreaRatio"] = round(measured_area, 6)
    return value


def _validate_mask(mask: Any) -> float:
    if not isinstance(mask, dict):
        raise HTTPException(status_code=422, detail={
            "code": "invalid_mask",
            "message": "each mask must be an object",
            "retryable": False,
        })
    points = mask.get("points")
    if points is not None:
        if not isinstance(points, list) or len(points) < 3:
            raise HTTPException(status_code=422, detail={
                "code": "invalid_mask",
                "message": "mask points must contain at least three points",
                "retryable": False,
            })
        normalized: list[tuple[float, float]] = []
        for point in points:
            if not isinstance(point, (list, tuple)) or len(point) != 2:
                raise HTTPException(status_code=422, detail={
                    "code": "invalid_mask",
                    "message": "mask points must be [x, y] pairs",
                    "retryable": False,
                })
            x, y = _finite_normalized_pair(point)
            normalized.append((x, y))
        area = abs(sum(
            normalized[index][0] * normalized[(index + 1) % len(normalized)][1]
            - normalized[(index + 1) % len(normalized)][0] * normalized[index][1]
            for index in range(len(normalized))
        )) / 2.0
        if area <= 0.0:
            raise HTTPException(status_code=422, detail={
                "code": "invalid_mask",
                "message": "mask area must be greater than zero",
                "retryable": False,
            })
        return area

    rect = mask.get("rect", mask)
    if not isinstance(rect, dict):
        raise HTTPException(status_code=422, detail={
            "code": "invalid_mask",
            "message": "mask rect must be an object",
            "retryable": False,
        })
    try:
        x = float(rect["x"])
        y = float(rect["y"])
        width = float(rect["width"])
        height = float(rect["height"])
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_mask",
            "message": "mask rect requires x, y, width, and height",
            "retryable": False,
        }) from exc
    if not all(math.isfinite(value) for value in (x, y, width, height)):
        valid = False
    else:
        valid = (
            0.0 <= x < 1.0
            and 0.0 <= y < 1.0
            and MIN_MASK_DIMENSION_RATIO <= width <= 1.0
            and MIN_MASK_DIMENSION_RATIO <= height <= 1.0
            and x + width <= 1.0
            and y + height <= 1.0
        )
    if not valid:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_mask",
            "message": "mask rect must be normalized, in bounds, and large enough to edit",
            "retryable": False,
        })
    return width * height


def _finite_normalized_pair(point: list[Any] | tuple[Any, Any]) -> tuple[float, float]:
    try:
        x, y = float(point[0]), float(point[1])
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=422, detail={
            "code": "invalid_mask",
            "message": "mask points must be numeric",
            "retryable": False,
        }) from exc
    if not all(math.isfinite(value) for value in (x, y)) or not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0):
        raise HTTPException(status_code=422, detail={
            "code": "invalid_mask",
            "message": "mask points must be normalized and in bounds",
            "retryable": False,
        })
    return x, y


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
    now = int(time.time() * 1000)
    with database.connect() as connection:
        active = connection.execute(
            "SELECT COUNT(*) FROM edit_jobs WHERE device_uuid = ? AND status IN ('queued','processing','validating')",
            (device_id,),
        ).fetchone()[0]
        recent = connection.execute(
            "SELECT COUNT(*) FROM edit_jobs WHERE device_uuid = ? AND created_at >= ?",
            (device_id, now - 60 * 60 * 1000),
        ).fetchone()[0]
    if active >= MAX_ACTIVE_JOBS:
        raise HTTPException(status_code=409, detail={
            "code": "active_job_limit",
            "message": "one edit job is already in progress",
            "retryable": True,
        })
    if recent >= MAX_JOBS_PER_HOUR:
        raise HTTPException(status_code=429, detail={
            "code": "hourly_job_limit",
            "message": "hourly edit job limit exceeded",
            "retryable": True,
        })
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
    if len(payload) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail={
            "code": "image_size_limit_exceeded",
            "message": f"image must be at most {MAX_UPLOAD_BYTES} bytes",
            "retryable": False,
        })
    try:
        with Image.open(io.BytesIO(payload)) as decoded:
            if max(decoded.size) > MAX_IMAGE_DIMENSION:
                raise ValueError("image dimensions exceed limit")
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
    results = database.get_results(job_id)
    if job["status"] == "done" and results:
        database.mark_results_delivered(job_id)
    return {
        "jobId": job["id"],
        "status": job["status"],
        "progressStage": job["progress_stage"],
        "results": [
            {
                "url": f"/files/{Path(file['storage_path']).name}",
                "generative": bool(file["generative"]),
                "validation": file["validation_status"],
                "seed": file["seed"],
            }
            for file in results
        ],
        "failReason": job["fail_reason"],
    }
