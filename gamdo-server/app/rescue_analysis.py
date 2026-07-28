from __future__ import annotations

import io
from typing import Any

from PIL import Image

from .reference_analysis import get_reference_analyzer


ANALYSIS_VERSION = 1
MAX_REFERENCES = 4


def analyze_rescue(
    payload: bytes,
    capture_ref: str = "",
    style_params: dict[str, Any] | None = None,
    reference_composition: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Return deterministic rescue suggestions without creating an edit job.

    The same detector used by reference analysis is intentionally reused. This
    keeps the recommendation and reference pipelines from disagreeing about
    the primary subject, while the actual GPU operation remains explicit.
    """
    with Image.open(io.BytesIO(payload)) as image:
        width, height = image.size
    analysis = get_reference_analyzer().analyze(payload)
    subjects = list(analysis.get("analysis", {}).get("subjects", []))
    recommendations: list[dict[str, Any]] = [{
        "id": "local_style",
        "kind": "local_style",
        "title": "내 감도로 정리하기",
        "reason": "현재 감도와 사진 상태에 맞춰 기본 보정을 적용합니다.",
        "operation": {"type": "local_style"},
        "confidence": 1.0,
    }]

    masks = _removal_masks(subjects)
    if masks:
        recommendations.append({
            "id": "remove_objects",
            "kind": "remove_objects",
            "title": "방해 요소 지우기",
            "reason": "사진 가장자리나 주 피사체 주변의 방해 요소를 정리할 수 있습니다.",
            "operation": {"type": "remove_objects", "masks": masks},
            "confidence": round(min(0.95, 0.55 + 0.1 * len(masks)), 3),
        })

    outpaint = _outpaint_recommendation(subjects, style_params, reference_composition)
    if outpaint is not None:
        recommendations.append(outpaint)

    return {
        "analysisVersion": ANALYSIS_VERSION,
        "captureRef": capture_ref,
        "image": {"width": width, "height": height},
        "analysis": analysis.get("analysis", {}),
        "recommendations": recommendations[:3],
        "capabilities": {
            "localStyle": True,
            "removeObjects": bool(masks),
            "outpaint": True,
        },
    }


def _removal_masks(subjects: list[dict[str, Any]]) -> list[dict[str, Any]]:
    masks: list[dict[str, Any]] = []
    for subject in subjects:
        if subject.get("role") == "person":
            continue
        x, y, width, height = subject.get("bbox", [0, 0, 0, 0])
        area = float(width) * float(height)
        # Recommendations are deliberately conservative: only small objects
        # near the border are offered as removable distractions.
        near_edge = x < 0.08 or y < 0.08 or x + width > 0.92 or y + height > 0.92
        if near_edge and 0.01 <= area <= 0.12:
            masks.append({"rect": {"x": x, "y": y, "width": width, "height": height}})
        if len(masks) == 8:
            break
    return masks


def _outpaint_recommendation(
    subjects: list[dict[str, Any]],
    style_params: dict[str, Any] | None,
    reference_composition: dict[str, Any] | None,
) -> dict[str, Any] | None:
    if not subjects:
        return None
    primary = next((item for item in subjects if item.get("role") == "person"), subjects[0])
    x, y, width, height = primary.get("bbox", [0, 0, 0, 0])
    margins = {"left": x, "top": y, "right": 1.0 - x - width, "bottom": 1.0 - y - height}
    target = _target_margin(style_params, reference_composition)
    direction, margin = min(margins.items(), key=lambda item: item[1])
    if margin > 0.03 and margin + 0.05 >= target:
        return None
    ratio = 0.15 if margin < 0.01 else 0.10 if margin < 0.03 else 0.05
    return {
        "id": "outpaint",
        "kind": "outpaint",
        "title": "여백 늘리기",
        "reason": "피사체가 화면 가장자리에 가까워 원하는 여백을 만들기 어렵습니다.",
        "operation": {"type": "outpaint", "direction": direction, "ratio": ratio},
        "confidence": round(min(0.95, 0.6 + (0.03 - min(margins.values()))), 3),
    }


def _target_margin(style_params: dict[str, Any] | None, reference: dict[str, Any] | None) -> float:
    if reference:
        slots = reference.get("layoutSlots") or []
        if slots:
            values = []
            for slot in slots:
                bounds = slot.get("bounds", []) if isinstance(slot, dict) else []
                if len(bounds) == 4:
                    x, y, width, height = (float(value) for value in bounds)
                    values.extend((x, y, 1 - x - width, 1 - y - height))
            if values:
                return max(0.05, min(0.35, sum(values) / len(values)))
    composition = (style_params or {}).get("composition", {})
    ranges = composition.get("backgroundRatioRange") or composition.get("backgroundRatio")
    if isinstance(ranges, list) and ranges:
        return max(0.05, min(0.35, float(ranges[-1]) * 0.25))
    return 0.08
