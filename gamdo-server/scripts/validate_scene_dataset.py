#!/usr/bin/env python3
"""Validate the license-aware GAMDO scene detection manifest.

The validator intentionally checks metadata and geometry only. It never opens
or copies image files, so running it cannot accidentally package third-party
dataset images into the Android application.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

CLASSES = {"person", "drinkware", "bag", "plant", "food_tableware", "unknown"}
SPLITS = {"train", "validation", "test"}


def error(line: int, message: str) -> str:
    return f"line {line}: {message}"


def valid_box(box: object, width: int, height: int) -> bool:
    if not isinstance(box, list) or len(box) != 4:
        return False
    left, top, right, bottom = box
    return all(isinstance(value, (int, float)) for value in box) and (
        0 <= left < right <= width and 0 <= top < bottom <= height
    )


def valid_polygon(polygon: object, width: int, height: int) -> bool:
    if not isinstance(polygon, list) or len(polygon) < 3:
        return False
    return all(
        isinstance(point, list)
        and len(point) == 2
        and all(isinstance(value, (int, float)) for value in point)
        and 0 <= point[0] <= width
        and 0 <= point[1] <= height
        for point in polygon
    )


def validate(path: Path, minimum_per_class: int, require_mask: bool) -> list[str]:
    problems: list[str] = []
    counts: Counter[str] = Counter()
    seen_images: set[str] = set()
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        return [str(exc)]

    for line_number, raw in enumerate(lines, start=1):
        if not raw.strip():
            continue
        try:
            record = json.loads(raw)
        except json.JSONDecodeError as exc:
            problems.append(error(line_number, f"invalid JSON: {exc.msg}"))
            continue
        if not isinstance(record, dict):
            problems.append(error(line_number, "record must be an object"))
            continue

        image = record.get("image")
        if not isinstance(image, str) or not image:
            problems.append(error(line_number, "image is required"))
        elif image in seen_images:
            problems.append(error(line_number, f"duplicate image: {image}"))
        else:
            seen_images.add(image)

        category = record.get("category")
        if category not in CLASSES:
            problems.append(error(line_number, f"unsupported category: {category!r}"))
        else:
            counts[category] += 1
        if record.get("split") not in SPLITS:
            problems.append(error(line_number, "split must be train, validation, or test"))
        if not isinstance(record.get("source"), str) or not record["source"]:
            problems.append(error(line_number, "source is required"))
        if not isinstance(record.get("license"), str) or not record["license"]:
            problems.append(error(line_number, "license is required"))
        if not isinstance(record.get("license_url"), str) or not record["license_url"]:
            problems.append(error(line_number, "license_url is required"))
        if not isinstance(record.get("commercial_use"), bool):
            problems.append(error(line_number, "commercial_use must be boolean"))

        width, height = record.get("width"), record.get("height")
        if not isinstance(width, int) or not isinstance(height, int) or width <= 0 or height <= 0:
            problems.append(error(line_number, "width and height must be positive integers"))
            continue

        instances = record.get("instances")
        if not isinstance(instances, list) or not instances:
            problems.append(error(line_number, "at least one instance is required"))
            continue
        for index, instance in enumerate(instances):
            prefix = f"instance {index}"
            if not isinstance(instance, dict):
                problems.append(error(line_number, f"{prefix} must be an object"))
                continue
            instance_category = instance.get("category")
            if instance_category not in CLASSES:
                problems.append(error(line_number, f"{prefix} has unsupported category"))
            if not valid_box(instance.get("bbox"), width, height):
                problems.append(error(line_number, f"{prefix} has invalid bbox"))
            if require_mask and not valid_polygon(instance.get("polygon"), width, height):
                problems.append(error(line_number, f"{prefix} requires a valid polygon"))
            elif "polygon" in instance and not valid_polygon(instance["polygon"], width, height):
                problems.append(error(line_number, f"{prefix} has invalid polygon"))

    if minimum_per_class:
        for category in sorted(CLASSES - {"unknown"}):
            if counts[category] < minimum_per_class:
                problems.append(
                    f"class {category!r} has {counts[category]} records; "
                    f"requires {minimum_per_class}"
                )
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--min-per-class", type=int, default=0)
    parser.add_argument("--require-mask", action="store_true")
    args = parser.parse_args()
    if args.min_per_class < 0:
        parser.error("--min-per-class cannot be negative")
    problems = validate(args.manifest, args.min_per_class, args.require_mask)
    if problems:
        print("scene dataset validation failed:", file=sys.stderr)
        print("\n".join(f"- {problem}" for problem in problems), file=sys.stderr)
        return 1
    print(f"scene dataset manifest OK: {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
