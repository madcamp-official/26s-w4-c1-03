from fastapi.testclient import TestClient

from app import main


def test_lifespan_warms_shared_reference_analyzer(monkeypatch) -> None:
    warmed: list[bool] = []
    monkeypatch.setattr(main, "get_reference_analyzer", lambda: warmed.append(True))

    with TestClient(main.app):
        assert warmed == [True]
