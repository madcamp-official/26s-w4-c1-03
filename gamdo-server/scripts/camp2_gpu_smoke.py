"""Run a disposable five-case LaMa smoke benchmark against ComfyUI."""

from __future__ import annotations

import argparse
import hashlib
import tempfile
import time
from pathlib import Path

from PIL import Image, ImageDraw

from app.comfyui_provider import ComfyUiProvider


def validate_candidate(candidate_path: Path, source_path: Path, seen_hashes: set[str]) -> dict[str, object]:
    """Apply the minimum integrity gate before a GPU candidate is accepted.

    This is deliberately not a perceptual quality score. It catches the failures
    that would make a demo dishonest or unusable: missing/corrupt PNG, changed
    resolution, and a fixed/original image returned in place of generation.
    """
    result_bytes = candidate_path.read_bytes()
    digest = hashlib.sha256(result_bytes).hexdigest()
    source_digest = hashlib.sha256(source_path.read_bytes()).hexdigest()
    if digest == source_digest:
        raise RuntimeError(f"candidate is byte-identical to input: {candidate_path}")
    if digest in seen_hashes:
        raise RuntimeError(f"candidate is duplicated across cases: {candidate_path}")
    seen_hashes.add(digest)
    with Image.open(source_path) as source, Image.open(candidate_path) as result:
        source.load()
        result.load()
        if result.format != "PNG":
            raise RuntimeError(f"candidate is not PNG: {candidate_path}")
        if result.size != source.size:
            raise RuntimeError(
                f"candidate resolution changed: {candidate_path} "
                f"{result.size} != {source.size}"
            )
        return {
            "seed": candidate_path.name,
            "size": result.size,
            "sha256": digest[:16],
        }


def build_case(path: Path, index: int) -> list[dict[str, object]]:
    image = Image.new("RGBA", (512, 512), (35 + index * 12, 100, 170, 255))
    draw = ImageDraw.Draw(image)
    left = 80 + index * 18
    top = 100 + index * 11
    width = 90 + index * 7
    height = 120 - index * 5
    draw.rectangle((left, top, left + width, top + height), fill=(220, 70, 70, 0))
    image.save(path)
    return [{
        "type": "remove_objects",
        "masks": [{"rect": {"x": left / 512, "y": top / 512, "width": width / 512, "height": height / 512}}],
    }]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--workflow", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args()
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="gamdo-camp2-smoke-") as root:
        root_path = Path(root)
        provider = ComfyUiProvider(args.url, args.workflow, root_path / "results", args.timeout)
        summaries: list[dict[str, object]] = []
        seen_hashes: set[str] = set()
        for index in range(5):
            input_path = root_path / f"case-{index}.png"
            operations = build_case(input_path, index)
            candidates = provider.remove_objects(input_path, operations, 2)
            if len(candidates) != 2:
                raise RuntimeError(f"case {index} returned {len(candidates)} candidates")
            integrity = [
                validate_candidate(candidate.path, input_path, seen_hashes)
                for candidate in candidates
            ]
            summaries.append({
                "case": index,
                "seeds": [candidate.seed for candidate in candidates],
                "integrity": integrity,
            })
        elapsed = time.monotonic() - started
    print({"cases": summaries, "elapsedSeconds": round(elapsed, 2)})


if __name__ == "__main__":
    main()
