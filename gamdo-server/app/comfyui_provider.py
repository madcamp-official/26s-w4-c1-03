from __future__ import annotations

import json
import os
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFilter

from .generative import GeneratedCandidate, GenerativeEditProvider, ProviderNotReady, UnavailableProvider


def is_outpaint_ready() -> bool:
    """Whether this deployment can actually execute a FLUX outpaint request.

    The API must not recommend an outpaint operation merely because the Python
    path supports one. Operations become available only after the deployment has
    explicitly supplied its dedicated workflow path; that is the final setup
    step after the matching FLUX model has been installed on ComfyUI.
    """
    base_url = os.getenv("GAMDO_COMFYUI_URL")
    workflow_value = os.getenv("GAMDO_COMFYUI_OUTPAINT_WORKFLOW")
    return bool(base_url and workflow_value and Path(workflow_value).is_file())


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
        temporary_upload: Path | None = None
        try:
            upload_path = image_path
            if _has_masks(operations):
                temporary_upload = _masked_upload_path(image_path, operations)
                upload_path = temporary_upload
            input_name = self._upload_image(upload_path)
            candidates: list[GeneratedCandidate] = []
            for seed in range(max(1, result_count)):
                seeded = _inject_workflow_inputs(workflow, input_name, operations, 1, seed)
                prompt = self._request_json("/prompt", {"prompt": seeded})
                prompt_id = prompt.get("prompt_id")
                if not prompt_id:
                    raise ProviderNotReady("ComfyUI did not return a prompt id")
                item = self._wait_for_history(str(prompt_id))
                candidates.extend(self._download_outputs(item["outputs"], str(prompt_id), 1, seed))
            return candidates
        finally:
            if temporary_upload is not None:
                temporary_upload.unlink(missing_ok=True)

    def outpaint(
        self,
        image_path: Path,
        operations: list[dict[str, Any]],
        result_count: int,
    ) -> list[GeneratedCandidate]:
        workflow_value = os.getenv("GAMDO_COMFYUI_OUTPAINT_WORKFLOW")
        workflow_path = Path(workflow_value) if workflow_value else None
        if not is_outpaint_ready() or workflow_path is None:
            raise ProviderNotReady("ComfyUI outpaint workflow is not configured")
        workflow = json.loads(workflow_path.read_text(encoding="utf-8"))
        operation = operations[0] if operations else {}
        prepared = _outpaint_upload_path(image_path, operation)
        try:
            input_name = self._upload_image(prepared)
            candidates: list[GeneratedCandidate] = []
            # AI3 deliberately keeps one candidate: outpaint is the slower,
            # optional path and must never multiply queue time.
            for seed in range(1):
                seeded = _inject_workflow_inputs(workflow, input_name, operations, 1, seed)
                prompt = self._request_json("/prompt", {"prompt": seeded})
                prompt_id = prompt.get("prompt_id")
                if not prompt_id:
                    raise ProviderNotReady("ComfyUI did not return an outpaint prompt id")
                item = self._wait_for_history(str(prompt_id))
                for candidate in self._download_outputs(item["outputs"], str(prompt_id), 1, seed):
                    _restore_outpaint_interior(candidate.path, image_path, operation)
                    candidates.append(GeneratedCandidate(candidate.path, candidate.seed, "outpaint"))
            return candidates
        finally:
            prepared.unlink(missing_ok=True)

    def _wait_for_history(self, prompt_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        while time.monotonic() < deadline:
            history = self._request_json(f"/history/{urllib.parse.quote(prompt_id)}")
            item = history.get(prompt_id)
            if item and item.get("status", {}).get("status_str") == "error":
                raise ProviderNotReady("ComfyUI workflow failed")
            if item and item.get("outputs"):
                return item
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

    def _upload_image(self, image_path: Path) -> str:
        boundary = "----gamdo-comfyui-boundary"
        data = image_path.read_bytes()
        filename = image_path.name
        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="image"; filename="{filename}"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode() + data + f"\r\n--{boundary}--\r\n".encode()
        request = urllib.request.Request(
            self.base_url + "/upload/image",
            data=body,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                result = json.loads(response.read().decode("utf-8"))
        except (OSError, ValueError, TimeoutError) as exc:
            raise ProviderNotReady("ComfyUI input upload failed") from exc
        name = result.get("name")
        if not name:
            raise ProviderNotReady("ComfyUI did not return an uploaded filename")
        return str(name)

    def _download_outputs(
        self,
        outputs: dict[str, Any],
        prompt_id: str,
        result_count: int,
        seed: int = 0,
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
        candidates.append(GeneratedCandidate(path=path, seed=seed + index, operation="remove_objects"))
        return candidates


def provider_from_environment() -> GenerativeEditProvider:
    """Build the configured provider without making an unconfigured server call."""
    base_url = os.getenv("GAMDO_COMFYUI_URL")
    workflow_value = os.getenv("GAMDO_COMFYUI_WORKFLOW")
    if not base_url or not workflow_value:
        return UnavailableProvider()
    return ComfyUiProvider(
        base_url=base_url,
        workflow_path=Path(workflow_value),
        output_dir=Path(os.getenv("GAMDO_GENERATED_OUTPUT_DIR", "storage/results")),
        timeout_seconds=float(os.getenv("GAMDO_COMFYUI_TIMEOUT_SECONDS", "120")),
    )


def _inject_workflow_inputs(
    workflow: dict[str, Any],
    input_name: str,
    operations: list[dict[str, Any]],
    result_count: int,
    seed: int = 0,
) -> dict[str, Any]:
    """Inject only deployment-defined placeholders; arbitrary workflow nodes stay intact."""
    def replace(value: Any) -> Any:
        if isinstance(value, dict):
            return {key: replace(item) for key, item in value.items()}
        if isinstance(value, list):
            return [replace(item) for item in value]
        if value == "${INPUT_IMAGE}":
            return input_name
        if value == "${RESULT_COUNT}":
            return result_count
        if value == "${SEED}":
            return seed
        if value == "${OPERATIONS_JSON}":
            return operations
        if isinstance(value, str):
            return value.replace("${INPUT_IMAGE}", input_name).replace(
                "${RESULT_COUNT}", str(result_count)
            ).replace("${SEED}", str(seed))
        return value

    return replace(workflow)


def _has_masks(operations: list[dict[str, Any]]) -> bool:
    return any(operation.get("masks") for operation in operations if isinstance(operation, dict))


def _outpaint_upload_path(image_path: Path, operation: dict[str, Any]) -> Path:
    """Prepare an expanded canvas; the workflow fills only the new edge."""
    direction = operation.get("direction")
    ratio = float(operation.get("ratio", 0.0))
    with Image.open(image_path) as source:
        source = source.convert("RGB")
        width, height = source.size
        extra_width = round(width * ratio) if direction in {"left", "right"} else 0
        extra_height = round(height * ratio) if direction in {"top", "bottom"} else 0
        canvas = Image.new("RGB", (width + extra_width, height + extra_height), (128, 128, 128))
        offset_x = extra_width if direction == "left" else 0
        offset_y = extra_height if direction == "top" else 0
        canvas.paste(source, (offset_x, offset_y))
    handle = tempfile.NamedTemporaryFile(prefix="gamdo-outpaint-", suffix=".png", delete=False)
    path = Path(handle.name)
    handle.close()
    canvas.save(path, format="PNG")
    return path


def _restore_outpaint_interior(candidate_path: Path, original_path: Path, operation: dict[str, Any]) -> None:
    """Restore original pixels after generation so the source is non-destructive."""
    with Image.open(original_path) as source, Image.open(candidate_path) as generated:
        source = source.convert("RGB")
        generated = generated.convert("RGB")
        direction = operation.get("direction")
        extra_x = generated.width - source.width if direction in {"left", "right"} else 0
        extra_y = generated.height - source.height if direction in {"top", "bottom"} else 0
        offset_x = extra_x if direction == "left" else 0
        offset_y = extra_y if direction == "top" else 0
        generated.paste(source, (offset_x, offset_y))
        generated.save(candidate_path, format="PNG")


def _masked_upload_path(image_path: Path, operations: list[dict[str, Any]]) -> Path:
    """Create a transient RGBA upload; the alpha channel is consumed as a mask by ComfyUI."""
    with Image.open(image_path) as source:
        image = source.convert("RGBA")
    mask = Image.new("L", image.size, 0)
    drawer = ImageDraw.Draw(mask)
    for operation in operations:
        for item in operation.get("masks", []):
            if not isinstance(item, dict):
                continue
            if isinstance(item.get("points"), list):
                points = [_point(item_point, image.size) for item_point in item["points"]]
                drawer.polygon(points, fill=255)
                continue
            rect = item.get("rect") or item
            if all(key in rect for key in ("x", "y", "width", "height")):
                x, y = _point((rect["x"], rect["y"]), image.size)
                w, h = _size((rect["width"], rect["height"]), image.size)
                drawer.rectangle((x, y, x + w, y + h), fill=255)
    # Expand the selected region by a small, resolution-aware margin. This
    # prevents untouched pixels at a hard drag boundary from producing halos.
    # The mask remains transient and is deleted in the provider finally block.
    dilation_ratio = float(os.getenv("GAMDO_MASK_DILATION_RATIO", "0.008"))
    if dilation_ratio > 0:
        kernel = max(3, min(31, round(min(image.size) * dilation_ratio) * 2 + 1))
        mask = mask.filter(ImageFilter.MaxFilter(kernel))
    image.putalpha(Image.eval(mask, lambda value: 255 - value))
    handle = tempfile.NamedTemporaryFile(prefix="gamdo-mask-", suffix=".png", delete=False)
    temporary = Path(handle.name)
    handle.close()
    image.save(temporary, format="PNG")
    return temporary


def _point(value: Any, size: tuple[int, int]) -> tuple[int, int]:
    x, y = float(value[0]), float(value[1])
    if 0 <= x <= 1 and 0 <= y <= 1:
        x *= size[0]
        y *= size[1]
    return round(x), round(y)


def _size(value: Any, size: tuple[int, int]) -> tuple[int, int]:
    width, height = float(value[0]), float(value[1])
    if 0 <= width <= 1 and 0 <= height <= 1:
        width *= size[0]
        height *= size[1]
    return round(width), round(height)
