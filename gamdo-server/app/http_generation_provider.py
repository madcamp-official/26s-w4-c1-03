from __future__ import annotations

import json
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from .generative import GeneratedCandidate, ProviderNotReady


class HttpOperationProvider:
    """Adapter for isolated CAMP-2 model services sharing the result directory."""

    def __init__(self, base_url: str | None, operation: str, timeout_seconds: float = 280.0) -> None:
        self.base_url = (base_url or "").rstrip("/")
        self.operation = operation
        self.timeout_seconds = timeout_seconds

    def generate(
        self,
        image_path: Path,
        operations: list[dict[str, Any]],
        result_count: int,
    ) -> list[GeneratedCandidate]:
        if not self.base_url:
            raise ProviderNotReady(f"{self.operation} provider is not configured")
        boundary = f"----gamdo-{uuid.uuid4().hex}"
        fields = {
            "operation": json.dumps(operations[0] if operations else {}, separators=(",", ":")),
            "resultCount": str(max(1, result_count)),
        }
        chunks: list[bytes] = []
        for name, value in fields.items():
            chunks.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
            )
        chunks.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{image_path.name}\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n".encode()
        )
        chunks.append(image_path.read_bytes())
        chunks.append(f"\r\n--{boundary}--\r\n".encode())
        request = urllib.request.Request(
            f"{self.base_url}/generate",
            data=b"".join(chunks),
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except (OSError, TimeoutError, ValueError) as exc:
            raise ProviderNotReady(f"{self.operation} provider request failed") from exc
        results = payload.get("results", [])
        candidates = [
            GeneratedCandidate(Path(item["path"]), int(item.get("seed", index)), self.operation)
            for index, item in enumerate(results)
            if isinstance(item, dict) and item.get("path") and Path(item["path"]).is_file()
        ]
        if not candidates:
            raise ProviderNotReady(f"{self.operation} provider returned no files")
        return candidates[: max(1, result_count)]


class CompositeGenerativeProvider:
    def __init__(self, comfy: Any, relight: HttpOperationProvider, viewpoint: HttpOperationProvider) -> None:
        self.comfy = comfy
        self.relight_provider = relight
        self.viewpoint_provider = viewpoint

    def remove_objects(self, image_path: Path, operations: list[dict[str, Any]], result_count: int):
        return self.comfy.remove_objects(image_path, operations, result_count)

    def outpaint(self, image_path: Path, operations: list[dict[str, Any]], result_count: int):
        return self.comfy.outpaint(image_path, operations, result_count)

    def relight(self, image_path: Path, operations: list[dict[str, Any]], result_count: int):
        return self.relight_provider.generate(image_path, operations, result_count)

    def viewpoint(self, image_path: Path, operations: list[dict[str, Any]], result_count: int):
        return self.viewpoint_provider.generate(image_path, operations, result_count)

