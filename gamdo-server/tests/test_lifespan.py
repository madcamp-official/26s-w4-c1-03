from __future__ import annotations

import asyncio

from app import main


def test_lifespan_warms_reference_analyzer(monkeypatch) -> None:
    calls: list[bool] = []

    def warmup() -> object:
        calls.append(True)
        return object()

    monkeypatch.setattr(main, "get_reference_analyzer", warmup)

    async def run() -> None:
        async with main.lifespan(main.app):
            pass

    asyncio.run(run())
    assert calls == [True]
