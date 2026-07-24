from __future__ import annotations

import json
from pathlib import Path


REQUIRED = {
    "id", "thumbnail", "subjectScale", "subjectPosition", "headroom", "backgroundRatio",
    "brightness", "lightType", "colorTemperature", "saturation", "contrast", "sharpness",
    "grain", "candidness", "framing",
}


def validate(path: Path) -> list[str]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    cards = payload.get("cards") if isinstance(payload, dict) else None
    errors: list[str] = []
    if payload.get("v") != 1 or not isinstance(cards, list) or not 15 <= len(cards) <= 20:
        errors.append("cards payload must be v1 with 15-20 cards")
        return errors
    ids = set()
    for index, card in enumerate(cards):
        missing = REQUIRED - set(card)
        errors.extend(f"card[{index}] missing {field}" for field in sorted(missing))
        if card.get("id") in ids:
            errors.append(f"duplicate card id: {card.get('id')}")
        ids.add(card.get("id"))
        for field in ("subjectScale", "subjectPosition", "headroom", "backgroundRatio", "brightness", "saturation", "contrast", "sharpness", "grain", "candidness", "framing"):
            value = card.get(field)
            if not isinstance(value, (int, float)) or not 0 <= value <= 1:
                errors.append(f"card[{index}].{field} must be in [0,1]")
        if not 3000 <= card.get("colorTemperature", 0) <= 7500:
            errors.append(f"card[{index}].colorTemperature is out of range")
    return errors


if __name__ == "__main__":
    target = Path(__file__).parents[2] / "app" / "src" / "main" / "assets" / "cards.json"
    errors = validate(target)
    if errors:
        print("card validation failed")
        print("\n".join(f"- {error}" for error in errors))
        raise SystemExit(1)
    print(f"card validation passed: {len(json.loads(target.read_text(encoding='utf-8'))['cards'])} cards")
