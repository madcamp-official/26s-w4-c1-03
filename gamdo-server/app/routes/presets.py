from __future__ import annotations

import hashlib
import json
from pathlib import Path

from fastapi import APIRouter, Depends, Header, Response
from fastapi.responses import JSONResponse

from .common import require_device_id


router = APIRouter()
PRESETS_PATH = Path(__file__).resolve().parents[2] / "presets.json"
PRESETS_BYTES = PRESETS_PATH.read_bytes()
PRESETS = json.loads(PRESETS_BYTES)
ETAG = '"' + hashlib.sha256(PRESETS_BYTES).hexdigest() + '"'


@router.get("/presets", dependencies=[Depends(require_device_id)])
def get_presets(if_none_match: str | None = Header(default=None)) -> Response:
    if if_none_match == ETAG:
        return Response(status_code=304, headers={"ETag": ETAG})
    return JSONResponse(
        content=PRESETS,
        headers={"ETag": ETAG, "Cache-Control": "private, max-age=300"},
    )
