"""Run a disposable five-case LaMa smoke benchmark against ComfyUI."""

from __future__ import annotations

import argparse
import tempfile
import time
from pathlib import Path

from PIL import Image, ImageDraw

from app.comfyui_provider import ComfyUiProvider


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
        for index in range(5):
            input_path = root_path / f"case-{index}.png"
            operations = build_case(input_path, index)
            candidates = provider.remove_objects(input_path, operations, 2)
            if len(candidates) != 2:
                raise RuntimeError(f"case {index} returned {len(candidates)} candidates")
            summaries.append({"case": index, "seeds": [candidate.seed for candidate in candidates]})
        elapsed = time.monotonic() - started
    print({"cases": summaries, "elapsedSeconds": round(elapsed, 2)})


if __name__ == "__main__":
    main()
