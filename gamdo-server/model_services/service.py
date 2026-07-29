from __future__ import annotations

import json
import logging
import os
import tempfile
import uuid
from pathlib import Path
from threading import Lock
from typing import Protocol

from fastapi import FastAPI, File, Form, HTTPException, UploadFile


logger = logging.getLogger(__name__)


class ModelBackend(Protocol):
    def ready(self) -> tuple[bool, str]: ...
    def generate(self, image: Path, operation: dict, output: Path, seed: int) -> None: ...


class ModelService:
    def __init__(self, operation: str, backend: ModelBackend, output_dir: Path) -> None:
        self.operation = operation
        self.backend = backend
        self.output_dir = output_dir
        self._lock = Lock()

    def health(self) -> dict:
        ready, reason = self.backend.ready()
        return {"status": "ready" if ready else "not_ready", "operation": self.operation, "reason": reason}

    def run(self, payload: bytes, requested: dict, result_count: int) -> list[dict]:
        if requested.get("type") != self.operation:
            raise ValueError("operation does not match this service")
        ready, reason = self.backend.ready()
        if not ready:
            raise RuntimeError(reason)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        results: list[dict] = []
        with tempfile.TemporaryDirectory(prefix=f"gamdo-{self.operation}-") as directory, self._lock:
            source = Path(directory) / "input.bin"
            source.write_bytes(payload)
            try:
                for index in range(max(1, min(result_count, 2))):
                    seed = int(requested.get("seed", 20260729)) + index
                    output = self.output_dir / f"{self.operation}_{uuid.uuid4().hex}.png"
                    try:
                        self.backend.generate(source, requested, output, seed)
                    except ValueError:
                        raise
                    except Exception as exc:
                        output.unlink(missing_ok=True)
                        logger.exception("model_execution_failed operation=%s", self.operation)
                        raise RuntimeError(f"{self.operation} model execution failed") from exc
                    if not output.is_file() or output.stat().st_size == 0:
                        raise RuntimeError("model backend produced no image")
                    results.append({"path": str(output), "seed": seed})
            finally:
                release = getattr(self.backend, "release", None)
                if callable(release):
                    release()
        return results


def create_app(service: ModelService) -> FastAPI:
    app = FastAPI(title=f"GAMDO {service.operation} model service")

    @app.get("/health")
    def health() -> dict:
        return service.health()

    @app.post("/generate")
    async def generate(
        image: UploadFile = File(...),
        operation: str = Form(...),
        result_count: int = Form(1, alias="resultCount"),
    ) -> dict:
        try:
            requested = json.loads(operation)
            if not isinstance(requested, dict):
                raise ValueError("operation must be an object")
            results = service.run(await image.read(20 * 1024 * 1024 + 1), requested, result_count)
            return {"results": results}
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except RuntimeError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc

    return app


def output_dir() -> Path:
    return Path(os.getenv("GAMDO_GENERATED_OUTPUT_DIR", "/opt/gamdo/server/storage/results"))
