from __future__ import annotations

import os
from pathlib import Path


def _workflow_ready(name: str) -> bool:
    value = os.getenv(name, "")
    return bool(value and Path(value).is_file())


def generation_capabilities() -> dict[str, bool]:
    """Deployment truth used by rescue cards; no feature is advertised by wish."""
    comfy_ready = bool(os.getenv("GAMDO_COMFYUI_URL"))
    return {
        "localStyle": True,
        "removeObjects": comfy_ready and _workflow_ready("GAMDO_COMFYUI_WORKFLOW"),
        "outpaint": comfy_ready and _workflow_ready("GAMDO_COMFYUI_OUTPAINT_WORKFLOW"),
        "relight": bool(os.getenv("GAMDO_RELIGHT_URL")),
        "viewpoint": bool(os.getenv("GAMDO_VIEWPOINT_URL")),
    }

