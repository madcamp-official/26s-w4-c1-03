from __future__ import annotations

from pathlib import Path

import pytest

from app.comfyui_provider import ComfyUiProvider, _inject_workflow_inputs, _masked_upload_path
from app.generative import ProviderNotReady


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
