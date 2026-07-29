from __future__ import annotations

from pathlib import Path

import pytest

from app.comfyui_provider import (
    ComfyUiProvider,
    _inject_workflow_inputs,
    _masked_upload_path,
    _outpaint_upload_path,
    _restore_outpaint_interior,
    provider_from_environment,
)
from app.generative import ProviderNotReady
from app.http_generation_provider import CompositeGenerativeProvider


def test_comfyui_provider_falls_back_without_deployment_config(tmp_path: Path) -> None:
    provider = ComfyUiProvider(None, tmp_path / "missing.json", tmp_path / "results")

    with pytest.raises(ProviderNotReady):
        provider.remove_objects(tmp_path / "input.png", [], 2)


def test_workflow_placeholders_use_uploaded_filename() -> None:
    workflow = {"image": "${INPUT_IMAGE}", "count": "${RESULT_COUNT}", "ops": "${OPERATIONS_JSON}"}

    injected = _inject_workflow_inputs(workflow, "upload.png", [{"type": "remove_objects"}], 2)

    assert injected["image"] == "upload.png"
    assert injected["count"] == 2
    assert injected["ops"] == [{"type": "remove_objects"}]


def test_workflow_placeholders_inject_seed() -> None:
    injected = _inject_workflow_inputs({"seed": "${SEED}"}, "input.png", [], 1, 7)
    assert injected["seed"] == 7


def test_masked_upload_uses_transient_alpha_mask(tmp_path: Path) -> None:
    source = tmp_path / "input.jpg"
    from PIL import Image

    Image.new("RGB", (100, 80), "white").save(source)
    masked = _masked_upload_path(
        source,
        [{"masks": [{"rect": {"x": 0.1, "y": 0.25, "width": 0.2, "height": 0.25}}]}],
    )
    try:
        with Image.open(masked) as image:
            assert image.mode == "RGBA"
            assert image.getpixel((20, 25))[3] == 0
            assert image.getpixel((90, 70))[3] == 255
    finally:
        masked.unlink(missing_ok=True)


def test_outpaint_upload_marks_only_expansion_as_transparent(tmp_path: Path) -> None:
    source = tmp_path / "input.jpg"
    from PIL import Image

    Image.new("RGB", (100, 80), "white").save(source)
    expanded = _outpaint_upload_path(source, {"type": "outpaint", "direction": "all", "ratio": 0.10})
    try:
        with Image.open(expanded) as image:
            assert image.mode == "RGBA"
            assert image.size == (128, 96)
            assert image.getpixel((0, 0))[3] == 0
            assert image.getpixel((10, 8))[3] == 255
            assert image.getpixel((109, 87))[3] == 255
            assert image.getpixel((119, 95))[3] == 0
    finally:
        expanded.unlink(missing_ok=True)


def test_outpaint_output_is_cropped_back_to_contract_size_and_restores_source(tmp_path: Path) -> None:
    from PIL import Image

    source = tmp_path / "input.png"
    candidate = tmp_path / "candidate.png"
    Image.new("RGB", (101, 79), "white").save(source)
    Image.new("RGB", (128, 96), "blue").save(candidate)

    _restore_outpaint_interior(candidate, source, {"type": "outpaint", "direction": "all", "ratio": 0.10})

    with Image.open(candidate) as restored:
        assert restored.size == (121, 95)
        assert restored.getpixel((10, 8)) == (255, 255, 255)
        assert restored.getpixel((0, 0)) == (0, 0, 255)


def test_provider_from_environment_defaults_to_safe_fallback(monkeypatch) -> None:
    monkeypatch.delenv("GAMDO_COMFYUI_URL", raising=False)
    monkeypatch.delenv("GAMDO_COMFYUI_WORKFLOW", raising=False)
    monkeypatch.delenv("GAMDO_RELIGHT_URL", raising=False)
    monkeypatch.delenv("GAMDO_VIEWPOINT_URL", raising=False)
    provider = provider_from_environment()
    assert isinstance(provider, CompositeGenerativeProvider)
    assert provider.comfy.__class__.__name__ == "UnavailableProvider"
    with pytest.raises(ProviderNotReady):
        provider.relight(Path("missing.png"), [], 1)
    with pytest.raises(ProviderNotReady):
        provider.viewpoint(Path("missing.png"), [], 1)


def test_provider_from_environment_builds_comfy_provider(monkeypatch, tmp_path: Path) -> None:
    workflow = tmp_path / "workflow.json"
    workflow.write_text("{}", encoding="utf-8")
    monkeypatch.setenv("GAMDO_COMFYUI_URL", "http://127.0.0.1:18188")
    monkeypatch.setenv("GAMDO_COMFYUI_WORKFLOW", str(workflow))
    monkeypatch.setenv("GAMDO_GENERATED_OUTPUT_DIR", str(tmp_path / "results"))
    provider = provider_from_environment()
    assert isinstance(provider, CompositeGenerativeProvider)
    assert isinstance(provider.comfy, ComfyUiProvider)
    assert provider.comfy.base_url == "http://127.0.0.1:18188"


def test_download_outputs_returns_each_downloaded_file_with_operation(monkeypatch, tmp_path: Path) -> None:
    provider = ComfyUiProvider("http://127.0.0.1:18188", None, tmp_path)

    class Response:
        def __enter__(self): return self
        def __exit__(self, *_args): return None
        def read(self): return b"png"

    monkeypatch.setattr("urllib.request.urlopen", lambda *_args, **_kwargs: Response())
    outputs = {"node": {"images": [
        {"filename": "a.png", "subfolder": "", "type": "output"},
        {"filename": "b.png", "subfolder": "", "type": "output"},
    ]}}
    candidates = provider._download_outputs(outputs, "prompt", 2, seed=7, operation="outpaint")
    assert len(candidates) == 2
    assert [item.operation for item in candidates] == ["outpaint", "outpaint"]
