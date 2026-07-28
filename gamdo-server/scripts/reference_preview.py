"""Create a visual approximation of the v3 colorTarget for QA.

The production color render remains Android LocalEditor. This tool is only a
side-by-side inspection aid for CAMP-2 analysis responses; it never replaces
the app renderer or uploads an image.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageEnhance, ImageOps


def apply_color_target(image: Image.Image, target: dict) -> Image.Image:
    result = image.convert("RGB")
    exposure = float(target.get("exposureBias", 0.0))
    result = ImageEnhance.Brightness(result).enhance(2.0 ** exposure)
    result = ImageEnhance.Contrast(result).enhance(1.0 + float(target.get("contrast", 0.0)))
    saturation = float(target.get("saturation", 0.0))
    result = ImageEnhance.Color(result).enhance(max(0.0, 1.0 + saturation))

    temperature = float(target.get("colorTemperature", 5200.0))
    warmth = max(-1.0, min(1.0, (temperature - 5200.0) / 2200.0))
    red = result.getchannel("R").point(lambda value: max(0, min(255, int(value * (1.0 + warmth * 0.08)))))
    blue = result.getchannel("B").point(lambda value: max(0, min(255, int(value * (1.0 - warmth * 0.08)))))
    result = Image.merge("RGB", (red, result.getchannel("G"), blue))

    fade = max(0.0, min(1.0, float(target.get("fade", 0.0))))
    if fade:
        result = Image.blend(result, Image.new("RGB", result.size, (235, 235, 225)), fade * 0.35)

    vignette = max(0.0, min(1.0, float(target.get("vignette", 0.0))))
    if vignette:
        mask = Image.new("L", result.size, 0)
        mask = Image.radial_gradient("L").resize(result.size).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        darkened = ImageEnhance.Brightness(result).enhance(1.0 - vignette * 0.35)
        result = Image.composite(darkened, result, mask)
    return result


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: reference_preview.py INPUT RESPONSE_JSON OUTPUT")
    input_path, response_path, output_path = map(Path, sys.argv[1:])
    response = json.loads(response_path.read_text(encoding="utf-8"))
    preview = apply_color_target(Image.open(input_path), response["colorTarget"])
    source = Image.open(input_path).convert("RGB")
    height = min(source.height, preview.height)
    source = source.resize((int(source.width * height / source.height), height))
    preview = preview.resize(source.size)
    canvas = Image.new("RGB", (source.width * 2, height), "white")
    canvas.paste(source, (0, 0))
    canvas.paste(preview, (source.width, 0))
    canvas.save(output_path, quality=95)
    print(output_path)


if __name__ == "__main__":
    main()
