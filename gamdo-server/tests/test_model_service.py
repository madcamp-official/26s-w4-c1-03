from __future__ import annotations

from pathlib import Path

import pytest

from app.provider_capabilities import _service_ready
from model_services.service import ModelService
from model_services.viewcrafter_service import ViewCrafterBackend


class FakeBackend:
    def __init__(self, ready: bool = True) -> None:
        self.is_ready = ready
        self.release_count = 0

    def ready(self) -> tuple[bool, str]:
        return self.is_ready, "test"

    def generate(self, image: Path, operation: dict, output: Path, seed: int) -> None:
        output.write_bytes(image.read_bytes() + str(seed).encode())

    def release(self) -> None:
        self.release_count += 1


def test_model_service_rejects_wrong_operation(tmp_path: Path) -> None:
    service = ModelService("relight", FakeBackend(), tmp_path / "results")
    with pytest.raises(ValueError):
        service.run(b"image", {"type": "viewpoint"}, 1)


def test_model_service_limits_candidates_to_two(tmp_path: Path) -> None:
    backend = FakeBackend()
    service = ModelService("relight", backend, tmp_path / "results")
    results = service.run(b"image", {"type": "relight", "seed": 7}, 5)
    assert len(results) == 2
    assert [item["seed"] for item in results] == [7, 8]
    assert backend.release_count == 1


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


def test_viewcrafter_partial_checkpoint_is_not_reported_ready(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    repository = tmp_path / "ViewCrafter"
    checkpoint = repository / "checkpoints" / "model.ckpt"
    dust3r = repository / "checkpoints" / "dust3r.pth"
    config = repository / "configs" / "inference.yaml"
    python = tmp_path / "python"
    checkpoint.parent.mkdir(parents=True)
    config.parent.mkdir(parents=True)
    checkpoint.write_bytes(b"partial")
    dust3r.write_bytes(b"complete-enough-for-this-test")
    python.write_bytes(b"executable")
    config.write_text("model: test", encoding="utf-8")
    monkeypatch.setenv("GAMDO_VIEWCRAFTER_REPOSITORY", str(repository))
    monkeypatch.setenv("GAMDO_VIEWCRAFTER_PYTHON", str(python))
    monkeypatch.setenv("GAMDO_VIEWCRAFTER_CHECKPOINT", str(checkpoint))
    monkeypatch.setenv("GAMDO_DUST3R_CHECKPOINT", str(dust3r))
    monkeypatch.setenv("GAMDO_VIEWCRAFTER_CONFIG", str(config))
    monkeypatch.setenv("GAMDO_VIEWCRAFTER_MIN_CHECKPOINT_BYTES", "8")
    monkeypatch.setenv("GAMDO_DUST3R_MIN_CHECKPOINT_BYTES", "8")

    ready, reason = ViewCrafterBackend().ready()

    assert ready is False
    assert "incomplete" in reason
