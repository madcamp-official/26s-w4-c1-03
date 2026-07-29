from __future__ import annotations

from pathlib import Path

import pytest

from app.provider_capabilities import _service_ready
from model_services.service import ModelService


class FakeBackend:
    def __init__(self, ready: bool = True) -> None:
        self.is_ready = ready

    def ready(self) -> tuple[bool, str]:
        return self.is_ready, "test"

    def generate(self, image: Path, operation: dict, output: Path, seed: int) -> None:
        output.write_bytes(image.read_bytes() + str(seed).encode())


def test_model_service_rejects_wrong_operation(tmp_path: Path) -> None:
    service = ModelService("relight", FakeBackend(), tmp_path / "results")
    with pytest.raises(ValueError):
        service.run(b"image", {"type": "viewpoint"}, 1)


def test_model_service_limits_candidates_to_two(tmp_path: Path) -> None:
    service = ModelService("relight", FakeBackend(), tmp_path / "results")
    results = service.run(b"image", {"type": "relight", "seed": 7}, 5)
    assert len(results) == 2
    assert [item["seed"] for item in results] == [7, 8]


def test_model_service_fails_closed_until_backend_is_ready(tmp_path: Path) -> None:
    service = ModelService("relight", FakeBackend(False), tmp_path / "results")
    assert service.health()["status"] == "not_ready"
    with pytest.raises(RuntimeError):
        service.run(b"image", {"type": "relight"}, 1)


def test_provider_capability_requires_ready_health(monkeypatch: pytest.MonkeyPatch) -> None:
    class Response:
        def __enter__(self): return self
        def __exit__(self, *_args): return None
        def read(self): return b'{"status":"ready"}'

    monkeypatch.setenv("GAMDO_TEST_MODEL_URL", "http://127.0.0.1:8199")
    monkeypatch.setattr("urllib.request.urlopen", lambda *_args, **_kwargs: Response())
    assert _service_ready("GAMDO_TEST_MODEL_URL") is True
