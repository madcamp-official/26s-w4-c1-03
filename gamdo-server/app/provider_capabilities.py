from __future__ import annotations

import os
import json
import urllib.request
from pathlib import Path


def _workflow_ready(name: str) -> bool:
    value = os.getenv(name, "")
    return bool(value and Path(value).is_file())


def _service_ready(name: str) -> bool:
    base_url = os.getenv(name, "").rstrip("/")
    if not base_url:
        return False
    try:
        with urllib.request.urlopen(f"{base_url}/health", timeout=0.5) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return payload.get("status") == "ready"
    except (OSError, TimeoutError, ValueError):
        return False


def generation_capabilities() -> dict[str, bool]:
    """Deployment truth used by rescue cards; no feature is advertised by wish."""
    comfy_ready = bool(os.getenv("GAMDO_COMFYUI_URL"))
    return {
        "localStyle": True,
        "removeObjects": comfy_ready and _workflow_ready("GAMDO_COMFYUI_WORKFLOW"),
        "outpaint": comfy_ready and _workflow_ready("GAMDO_COMFYUI_OUTPAINT_WORKFLOW"),
        "relight": _service_ready("GAMDO_RELIGHT_URL"),
        "viewpoint": _service_ready("GAMDO_VIEWPOINT_URL"),
    }
