from __future__ import annotations

import io
import logging
from typing import Any

from PIL import Image, ImageOps

from .reference_analysis import get_reference_analyzer
from .provider_capabilities import generation_capabilities


ANALYSIS_VERSION = 2
MAX_REFERENCES = 4
logger = logging.getLogger(__name__)


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
        image.load()
        # Same fix as reference_analysis.ReferenceAnalyzer.analyze: bake in
        # EXIF Orientation before reading dimensions, so the width/height
        # reported here agree with the (separately analyzed, separately
        # transposed) composition/horizon fields below on what "up" means
        # for this payload. Same try/except posture (O-8) as
        # storage.save_exif_stripped_input: a malformed Orientation tag must
        # not fail the rescue-analysis request.
        try:
            image = ImageOps.exif_transpose(image) or image
        except Exception:
            logger.warning(
                "rescue_exif_orientation_transpose_failed; measuring pixels as decoded",
                exc_info=True,
            )
        width, height = image.size
        lighting = _lighting_diagnosis(image.convert("RGB"))
    analysis = get_reference_analyzer().analyze(payload)
    subjects = list(analysis.get("analysis", {}).get("subjects", []))
    capabilities = generation_capabilities()
    recommendations: list[dict[str, Any]] = [{
        "id": "local_style",
        "kind": "local_style",
        "title": "내 감도로 정리하기",
        "reason": "현재 감도와 사진 상태에 맞춰 기본 보정을 적용합니다.",
        "operation": {"type": "local_style"},
        "confidence": 1.0,
    }]

    outpaint = _outpaint_recommendation(subjects, style_params, reference_composition)
    if outpaint is not None and capabilities["outpaint"]:
        recommendations.append(outpaint)

    if capabilities["relight"] and lighting["backlightScore"] >= 0.18:
        recommendations.append({
            "id": "relight",
            "kind": "relight",
            "title": "빛 균형 맞추기",
            "reason": "피사체보다 뒤쪽이 밝아 얼굴과 주요 피사체가 어둡게 보입니다.",
            "operation": {"type": "relight", "direction": "front", "strength": 0.65},
            "confidence": round(min(0.95, 0.55 + lighting["backlightScore"]), 3),
        })

    viewpoint = _viewpoint_recommendation(subjects)
    if viewpoint is not None and capabilities["viewpoint"]:
        recommendations.append(viewpoint)

    # Object removal remains available from Direct edit, but is deliberately
    # not a primary recommendation after AI3's composition-first product change.
    masks = _removal_masks(subjects)

    return {
        "analysisVersion": ANALYSIS_VERSION,
        "captureRef": capture_ref,
        "image": {"width": width, "height": height},
        "analysis": analysis.get("analysis", {}),
        "recommendations": recommendations[:3],
        "diagnostics": {"lighting": lighting},
        "capabilities": {**capabilities, "removeObjects": capabilities["removeObjects"] and bool(masks)},
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


def _viewpoint_recommendation(subjects: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not subjects:
        return None
    primary = next((item for item in subjects if item.get("role") == "person"), subjects[0])
    x, _, width, _ = primary.get("bbox", [0, 0, 0, 0])
    center = float(x) + float(width) / 2.0
    if 0.35 <= center <= 0.65:
        return None
    motion = "left" if center > 0.65 else "right"
    return {
        "id": "viewpoint",
        "kind": "viewpoint",
        "title": "보는 위치 바꾸기",
        "reason": "피사체가 한쪽으로 치우쳐 작은 시점 이동으로 구도를 다시 만들 수 있습니다.",
        "operation": {"type": "viewpoint", "motion": motion, "strength": "subtle"},
        "confidence": round(min(0.9, 0.55 + abs(center - 0.5)), 3),
    }


def _lighting_diagnosis(image: Image.Image) -> dict[str, float]:
    sample = image.resize((96, 96)).convert("L")
    pixels = list(sample.getdata())
    center: list[int] = []
    border: list[int] = []
    for y in range(96):
        for x in range(96):
            value = pixels[y * 96 + x]
            if 24 <= x < 72 and 20 <= y < 82:
                center.append(value)
            else:
                border.append(value)
    center_luma = sum(center) / max(1, len(center)) / 255.0
    border_luma = sum(border) / max(1, len(border)) / 255.0
    return {
        "centerLuminance": round(center_luma, 4),
        "borderLuminance": round(border_luma, 4),
        "backlightScore": round(max(0.0, border_luma - center_luma), 4),
    }
