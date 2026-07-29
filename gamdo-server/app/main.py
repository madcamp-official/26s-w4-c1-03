from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from .db import Database
from .reference_analysis import get_reference_analyzer
from .storage import RESULT_DIR, ensure_storage
from .routes import edit_jobs, presets, references, rescue, shoot_sessions


@asynccontextmanager
async def lifespan(_: FastAPI):
    ensure_storage()
    Database().initialize()
    # Load and warm the optional GPU reference analyzer before accepting the
    # first request. The analyzer is CPU-safe when models are disabled or
    # unavailable, while production CAMP-2 keeps model compilation out of the
    # Android request timeout window.
    await asyncio.to_thread(get_reference_analyzer)
    yield


app = FastAPI(title="GAMDO Server", version="0.1.0", lifespan=lifespan)
app.mount("/files", StaticFiles(directory=str(RESULT_DIR)), name="generated-files")


def error_payload(code: str, message: str, retryable: bool) -> dict[str, Any]:
    return {"code": code, "message": message, "retryable": retryable}


@app.exception_handler(HTTPException)
async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
    detail = exc.detail if isinstance(exc.detail, dict) else error_payload(
        "http_error", str(exc.detail), exc.status_code >= 500
    )
    return JSONResponse(status_code=exc.status_code, content=detail)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content=error_payload("invalid_request", str(exc.errors()), False),
    )


@app.get("/health", tags=["system"])
def health() -> dict[str, str]:
    return {"status": "ok"}


app.include_router(presets.router, prefix="/api/v1", tags=["presets"])
app.include_router(references.router, prefix="/api/v1", tags=["references"])
app.include_router(edit_jobs.router, prefix="/api/v1", tags=["edit-jobs"])
app.include_router(rescue.router, prefix="/api/v1", tags=["rescue"])
app.include_router(shoot_sessions.router, prefix="/api/v1", tags=["shoot-sessions"])
