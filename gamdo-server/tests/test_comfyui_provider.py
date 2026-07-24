from __future__ import annotations

from pathlib import Path

import pytest

from app.comfyui_provider import ComfyUiProvider
from app.generative import ProviderNotReady


def test_comfyui_provider_falls_back_without_deployment_config(tmp_path: Path) -> None:
    provider = ComfyUiProvider(None, tmp_path / "missing.json", tmp_path / "results")

    with pytest.raises(ProviderNotReady):
        provider.remove_objects(tmp_path / "input.png", [], 2)
