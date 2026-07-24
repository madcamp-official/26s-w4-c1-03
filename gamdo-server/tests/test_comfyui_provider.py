from __future__ import annotations

from pathlib import Path

import pytest

from app.comfyui_provider import ComfyUiProvider, _inject_workflow_inputs
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
