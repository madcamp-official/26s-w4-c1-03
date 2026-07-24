from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from .generative import GeneratedCandidate, GenerativeEditProvider, ProviderNotReady


class ComfyUiProvider(GenerativeEditProvider):
    """Small HTTP adapter for a headless ComfyUI instance.

    The workflow JSON is supplied by deployment configuration. No provider is
    contacted when either the URL or workflow is absent, preserving fallback.
    """

    def __init__(
        self,
        base_url: str | None,
        workflow_path: Path | None,
        output_dir: Path,
        timeout_seconds: float = 90.0,
    ) -> None:
        self.base_url = (base_url or "").rstrip("/")
        self.workflow_path = workflow_path
        self.output_dir = output_dir
        self.timeout_seconds = timeout_seconds

    def remove_objects(
        self,
        image_path: Path,
        operations: list[dict[str, Any]],
        result_count: int,
    ) -> list[GeneratedCandidate]:
        if not self.base_url or self.workflow_path is None or not self.workflow_path.exists():
            raise ProviderNotReady("ComfyUI URL or workflow is not configured")
        workflow = json.loads(self.workflow_path.read_text(encoding="utf-8"))
        workflow = _inject_workflow_inputs(workflow, image_path, operations, result_count)
        prompt = self._request_json("/prompt", {"prompt": workflow})
        prompt_id = prompt.get("prompt_id")
        if not prompt_id:
            raise ProviderNotReady("ComfyUI did not return a prompt id")

        deadline = time.monotonic() + self.timeout_seconds
        while time.monotonic() < deadline:
            history = self._request_json(f"/history/{urllib.parse.quote(prompt_id)}")
            item = history.get(prompt_id)
            if item and item.get("outputs"):
                return self._download_outputs(item["outputs"], prompt_id, result_count)
            time.sleep(0.5)
        raise TimeoutError("ComfyUI job timed out")

    def _request_json(self, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST" if body is not None else "GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                return json.loads(response.read().decode("utf-8"))
        except (OSError, ValueError, TimeoutError) as exc:
            raise ProviderNotReady("ComfyUI request failed") from exc

    def _download_outputs(
        self,
        outputs: dict[str, Any],
        prompt_id: str,
        result_count: int,
    ) -> list[GeneratedCandidate]:
        self.output_dir.mkdir(parents=True, exist_ok=True)
        candidates: list[GeneratedCandidate] = []
        for node_output in outputs.values():
            for index, image in enumerate(node_output.get("images", [])):
                if len(candidates) >= result_count:
                    return candidates
                query = urllib.parse.urlencode({
                    "filename": image["filename"],
                    "subfolder": image.get("subfolder", ""),
                    "type": image.get("type", "output"),
                })
                request = urllib.request.Request(f"{self.base_url}/view?{query}")
                try:
                    with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                        path = self.output_dir / f"{prompt_id}_{index}.png"
                        path.write_bytes(response.read())
                except OSError as exc:
                    raise ProviderNotReady("ComfyUI output download failed") from exc
                candidates.append(GeneratedCandidate(path=path, seed=index, operation="remove_objects"))
        return candidates


def _inject_workflow_inputs(
    workflow: dict[str, Any],
    image_path: Path,
    operations: list[dict[str, Any]],
    result_count: int,
) -> dict[str, Any]:
    """Inject only deployment-defined placeholders; arbitrary workflow nodes stay intact."""
    serialized = json.dumps(workflow)
    serialized = serialized.replace("${INPUT_IMAGE}", str(image_path))
    serialized = serialized.replace("${RESULT_COUNT}", str(result_count))
    serialized = serialized.replace("${OPERATIONS_JSON}", json.dumps(operations, ensure_ascii=False))
    return json.loads(serialized)
