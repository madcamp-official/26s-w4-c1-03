from __future__ import annotations

import io
from collections import Counter
from typing import Any

from PIL import Image


def analyze_reference(payload: bytes) -> dict[str, Any]:
    """Analyze a reference in memory; no input bytes are written to disk."""
    image = Image.open(io.BytesIO(payload)).convert("RGB")
    image.thumbnail((256, 256), Image.Resampling.LANCZOS)
    pixels = list(image.getdata())
    width, height = image.size
    subject = _skin_candidate_box(pixels, width, height)
    horizon = _estimate_horizon(pixels, width, height)
    subjects = []
    if subject is not None:
        left, top, right, bottom = subject
        subjects.append({
            "bbox": [round(left, 4), round(top, 4), round(right - left, 4), round(bottom - top, 4)],
            "faceSize": round((right - left) * (bottom - top), 4),
            "pose": {
                "centerX": round((left + right) / 2, 4),
                "centerY": round((top + bottom) / 2, 4),
                "confidence": 0.35,
            },
            "landmarks": _landmarks(left, top, right, bottom),
        })

    palette = _palette(image)
    average = tuple(sum(channel[index] for channel in pixels) / max(len(pixels), 1) for index in range(3))
    aspect = width / max(height, 1)
    target_aspect = "1:1" if 0.9 <= aspect <= 1.1 else "4:5"
    subject_box = subjects[0]["bbox"] if subjects else None
    subject_scale = subject_box[3] if subject_box else 0.4
    subject_x = (subject_box[0] + subject_box[2] / 2) if subject_box else 0.5
    subject_y = subject_box[1] if subject_box else 0.05
    color_temperature = int(max(3000, min(7500, 5200 + (average[0] - average[2]) * 12)))

    mean_luminance = _mean_luminance(pixels)
    contrast = _histogram_contrast(pixels)
    return {
        "analysisVersion": 2,
        "analysis": {
            "peopleCount": len(subjects),
            "subjects": subjects,
            "cameraHeight": "chest_level" if subjects else "unknown",
            "horizon": round(horizon, 4),
            "tilt": 0.0,
            "backgroundRatio": round(max(0.0, 1.0 - subject_scale * (subject_box[2] if subject_box else 0.0)), 4),
            "aspectRatio": target_aspect,
            "palette": palette,
            "colorTemperature": color_temperature,
            "luminanceHistogram": _luminance_histogram(pixels),
            "poseConfidence": round(subjects[0]["pose"]["confidence"], 4) if subjects else 0.0,
        },
        "targetComposition": {
            "targetAspectRatio": target_aspect,
            "subjectScaleRange": [round(max(0.2, subject_scale * 0.8), 4), round(min(0.8, subject_scale * 1.2), 4)],
            "subjectPosition": _subject_position(subject_x),
            "headroomRange": [round(max(0.02, subject_y * 0.8), 4), round(min(0.3, subject_y * 1.2 + 0.02), 4)],
            "horizonPosition": round(horizon, 4),
            "cameraPitchRange": [-5, 5],
            "posePattern": "portrait" if subjects else "static",
            "backgroundRatio": [round(max(0.0, 1.0 - subject_scale), 4), 0.85],
        },
        "colorTarget": {
            "palette": palette,
            "colorTemperature": color_temperature,
            "exposureBias": round((0.5 - mean_luminance) * 1.2, 4),
            "contrast": round((contrast - 0.18) * 1.8, 4),
            "saturation": round(_saturation_bias(pixels), 4),
            "fade": round(max(0.0, 0.25 - contrast), 4),
            "grain": 0.08 if contrast < 0.16 else 0.02,
            "vignette": 0.08,
        },
    }


def _landmarks(left: float, top: float, right: float, bottom: float) -> dict[str, list[float]]:
    """Conservative normalized landmarks used only as a guide target.

    The optional GPU pose adapter can replace these values without changing the
    response shape. Low confidence makes the client keep the static fallback.
    """
    center_x = (left + right) / 2
    return {
        "head": [round(center_x, 4), round(top, 4)],
        "leftShoulder": [round(left, 4), round(top + (bottom - top) * 0.28, 4)],
        "rightShoulder": [round(right, 4), round(top + (bottom - top) * 0.28, 4)],
        "leftHip": [round(left, 4), round(top + (bottom - top) * 0.72, 4)],
        "rightHip": [round(right, 4), round(top + (bottom - top) * 0.72, 4)],
    }


def _skin_candidate_box(pixels: list[tuple[int, int, int]], width: int, height: int) -> tuple[float, float, float, float] | None:
    points: list[tuple[int, int]] = []
    for index, (red, green, blue) in enumerate(pixels):
        # Conservative skin-colour candidate heuristic; a future MediaPipe adapter
        # can replace this without changing the API response contract.
        if red > 70 and red > green * 1.03 and green > blue * 1.08 and red - blue > 25:
            points.append((index % width, index // width))
    if len(points) < max(12, width * height // 500):
        return None
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return min(xs) / width, min(ys) / height, (max(xs) + 1) / width, (max(ys) + 1) / height


def _palette(image: Image.Image) -> list[str]:
    quantized = image.quantize(colors=5, method=Image.Quantize.MEDIANCUT)
    colors = quantized.getcolors(maxcolors=256) or []
    colors.sort(reverse=True)
    palette = quantized.getpalette()
    result = []
    for _, color_index in colors[:5]:
        offset = color_index * 3
        result.append("#%02X%02X%02X" % tuple(palette[offset:offset + 3]))
    if not result:
        result = ["#808080"]
    while len(result) < 5:
        result.append(result[-1])
    return result


def _luminance_histogram(pixels: list[tuple[int, int, int]], bins: int = 16) -> list[int]:
    histogram = [0] * bins
    for red, green, blue in pixels:
        luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255
        histogram[min(bins - 1, int(luminance * bins))] += 1
    return histogram


def _mean_luminance(pixels: list[tuple[int, int, int]]) -> float:
    if not pixels:
        return 0.0
    return sum((0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 for r, g, b in pixels) / len(pixels)


def _histogram_contrast(pixels: list[tuple[int, int, int]]) -> float:
    values = sorted((0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 for r, g, b in pixels)
    if not values:
        return 0.0
    low = values[max(0, int(len(values) * 0.05))]
    high = values[min(len(values) - 1, int(len(values) * 0.95))]
    return max(0.0, high - low)


def _saturation_bias(pixels: list[tuple[int, int, int]]) -> float:
    if not pixels:
        return 0.0
    return sum((max(rgb) - min(rgb)) / 255 for rgb in pixels) / len(pixels) - 0.25


def _estimate_horizon(pixels: list[tuple[int, int, int]], width: int, height: int) -> float:
    """Find the strongest horizontal luminance transition, with a safe fallback."""
    if width < 2 or height < 4:
        return 0.5
    row_means = []
    for y in range(height):
        row = pixels[y * width:(y + 1) * width]
        row_means.append(_mean_luminance(row))
    index = max(range(1, height - 1), key=lambda y: abs(row_means[y] - row_means[y - 1]))
    return index / height


def _subject_position(center_x: float) -> str:
    if center_x < 0.42:
        return "third_left"
    if center_x > 0.58:
        return "third_right"
    return "center"
