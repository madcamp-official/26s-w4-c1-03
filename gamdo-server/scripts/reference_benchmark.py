from __future__ import annotations

import io
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from PIL import Image, ImageDraw

from app.reference_analysis import analyze_reference, get_reference_analyzer


def sample(index: int) -> bytes:
    image = Image.new("RGB", (640, 800), (80 + index * 9, 110 + index * 3, 145 - index * 4))
    draw = ImageDraw.Draw(image)
    left = 40 + (index % 5) * 90
    top = 80 + (index % 4) * 35
    draw.ellipse((left, top, left + 130, top + 130), fill=(190, 125, 95))
    draw.rectangle((left + 25, top + 120, left + 105, top + 410), fill=(40 + index * 5, 80, 120))
    output = io.BytesIO()
    image.save(output, format="JPEG", quality=90)
    return output.getvalue()


def main() -> None:
    # Production warms the analyzer during FastAPI lifespan. Do the same here
    # so model loading is not incorrectly counted as request latency.
    get_reference_analyzer()
    durations = []
    for index in range(10):
        started = time.perf_counter()
        result = analyze_reference(sample(index))
        durations.append((time.perf_counter() - started) * 1000)
        assert len(result["analysis"]["palette"]) == 5
        assert len(result["analysis"]["luminanceHistogram"]) == 16
        assert "targetComposition" in result and "colorTarget" in result
    print(f"reference analysis passed: 10/10")
    print(f"latency_ms: min={min(durations):.2f} median={statistics.median(durations):.2f} max={max(durations):.2f}")
    if max(durations) > 5000:
        raise SystemExit("reference analysis exceeded 5 second limit")


if __name__ == "__main__":
    main()
