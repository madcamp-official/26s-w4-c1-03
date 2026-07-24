from __future__ import annotations

import json
import sys
from pathlib import Path


REQUIRED_IDS = {
    "clean_social",
    "candid_feed",
    "bright_review",
    "soft_film",
    "casual_portrait",
    "night_street",
}
REQUIRED_COMPOSITION = {
    "targetAspectRatio",
    "subjectScaleRange",
    "subjectPosition",
    "headroomRange",
    "horizonPosition",
    "cameraPitchRange",
}
REQUIRED_COLOR = {
    "exposureBias",
    "colorTemperature",
    "contrast",
    "saturation",
    "grain",
    "vignette",
    "fade",
}


def validate(path: Path) -> list[str]:
    errors: list[str] = []
    try:
        presets = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return [f"cannot read JSON: {exc}"]
    if not isinstance(presets, list) or len(presets) != 6:
        errors.append("presets must contain exactly 6 items")
        return errors
    ids = {item.get("id") for item in presets if isinstance(item, dict)}
    if ids != REQUIRED_IDS:
        errors.append(f"preset ids mismatch: {sorted(ids)}")
    for index, preset in enumerate(presets):
        prefix = f"preset[{index}]"
        if not isinstance(preset, dict):
            errors.append(f"{prefix} must be an object")
            continue
        for field in ("id", "name", "displayName", "composition", "color"):
            if field not in preset:
                errors.append(f"{prefix} missing {field}")
        composition = preset.get("composition", {})
        color = preset.get("color", {})
        missing = REQUIRED_COMPOSITION - set(composition)
        errors.extend(f"{prefix}.composition missing {field}" for field in sorted(missing))
        missing = REQUIRED_COLOR - set(color)
        errors.extend(f"{prefix}.color missing {field}" for field in sorted(missing))
        for field in ("subjectScaleRange", "headroomRange", "cameraPitchRange"):
            value = composition.get(field)
            if not isinstance(value, list) or len(value) != 2 or value[0] > value[1]:
                errors.append(f"{prefix}.composition.{field} must be an ordered pair")
        if composition.get("subjectPosition") not in {"center", "third_left", "third_right"}:
            errors.append(f"{prefix}.composition.subjectPosition is invalid")
        if not 0 <= composition.get("horizonPosition", -1) <= 1:
            errors.append(f"{prefix}.composition.horizonPosition must be in [0, 1]")
        if not 3000 <= color.get("colorTemperature", 0) <= 7500:
            errors.append(f"{prefix}.color.colorTemperature must be in [3000, 7500]")
    return errors


if __name__ == "__main__":
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parents[1] / "presets.json"
    problems = validate(target)
    if problems:
        print("preset validation failed")
        print("\n".join(f"- {problem}" for problem in problems))
        raise SystemExit(1)
    print(f"preset validation passed: 6 presets ({target})")
