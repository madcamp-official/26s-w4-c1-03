from __future__ import annotations

from fastapi import Header, HTTPException


def require_device_id(x_device_id: str | None = Header(default=None, alias="X-Device-Id")) -> str:
    if not x_device_id or len(x_device_id) > 128:
        raise HTTPException(status_code=400, detail={
            "code": "missing_device_id",
            "message": "X-Device-Id header is required",
            "retryable": False,
        })
    return x_device_id

