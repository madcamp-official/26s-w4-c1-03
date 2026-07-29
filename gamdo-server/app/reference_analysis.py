from __future__ import annotations

import io
import logging
import os
import threading
from typing import Any

from PIL import Image


ANALYSIS_VERSION = 3
MAX_PIXELS = 24_000_000
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
_analyzer_lock = threading.Lock()
_analyzer: "ReferenceAnalyzer | None" = None
logger = logging.getLogger(__name__)


def analyze_reference(payload: bytes) -> dict[str, Any]:
    """Analyze a reference in memory; never persist the uploaded bytes.

    GPU models are opt-in through ``GAMDO_REFERENCE_MODELS=1``. A model failure
    is deliberately represented as ``composition=false`` instead of fabricating
    a subject box from skin-colour pixels.
    """
    return get_reference_analyzer().analyze(payload)


def get_reference_analyzer() -> "ReferenceAnalyzer":
    global _analyzer
    if _analyzer is None:
        with _analyzer_lock:
            if _analyzer is None:
                _analyzer = ReferenceAnalyzer.load()
    return _analyzer


class ReferenceAnalyzer:
    def __init__(self, segmenter: Any = None, pose_model: Any = None, face_model: Any = None):
        self.segmenter = segmenter
        self.pose_model = pose_model
        self.face_model = face_model

    @classmethod
    def load(cls) -> "ReferenceAnalyzer":
        if os.getenv("GAMDO_REFERENCE_MODELS", "0") != "1":
            return cls()
        try:
            from ultralytics import YOLO

            segmenter = YOLO(os.getenv("GAMDO_REFERENCE_SEG_MODEL", "yolo11s-seg.pt"))
            pose_model = YOLO(os.getenv("GAMDO_REFERENCE_POSE_MODEL", "yolo11n-pose.pt"))
            face_model = None
            if os.getenv("GAMDO_REFERENCE_FACE", "1") == "1":
                try:
                    from insightface.app import FaceAnalysis

                    providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
                    face_model = FaceAnalysis(name="buffalo_l", allowed_modules=["detection"], providers=providers)
                    face_model.prepare(ctx_id=0, det_size=(640, 640))
                except Exception:
                    # Object/pose analysis remains useful when the optional face
                    # runtime cannot load. Never discard the working models.
                    face_model = None
                    logger.warning("reference_face_model_unavailable", exc_info=True)
            instance = cls(segmenter, pose_model, face_model)
            instance.warmup()
            logger.info(
                "reference_models_ready segmenter=%s pose=%s face=%s",
                segmenter.__class__.__name__,
                pose_model.__class__.__name__,
                face_model.__class__.__name__ if face_model is not None else "none",
            )
            return instance
        except Exception:
            # The service remains usable for deterministic colour analysis, but
            # it must not return a guessed composition when models are absent.
            logger.warning("reference_models_unavailable", exc_info=True)
            return cls()

    def warmup(self) -> None:
        if self.segmenter is None:
            return
        # Warm the same input scale used by `analyze()`. A 256px warmup leaves
        # CUDA/TensorRT kernels for the first 1024px reference request cold and
        # made the first request exceed the 5s contract on CAMP-2.
        sample = Image.new("RGB", (1024, 1024), (128, 128, 128))
        self.segmenter(sample, verbose=False)
        if self.pose_model is not None:
            self.pose_model(sample, verbose=False)
        if self.face_model is not None:
            self._face_candidates(sample)

    def analyze(self, payload: bytes) -> dict[str, Any]:
        image = Image.open(io.BytesIO(payload)).convert("RGB")
        image.thumbnail((1024, 1024), Image.Resampling.LANCZOS)
        pixels = list(image.getdata())
        width, height = image.size
        palette = _palette(image)
        horizon = _estimate_horizon(pixels, width, height)
        color_target = _color_target(pixels, palette)
        subjects = self._model_subjects(image) if self.segmenter is not None else []
        composition_available = bool(subjects)
        slots = [_slot_for_subject(subject) for subject in subjects[:4]]
        primary = subjects[0] if subjects else None
        primary_box = primary["bbox"] if primary else None
        subject_scale = primary_box[3] if primary_box else 0.0
        subject_x = (primary_box[0] + primary_box[2] / 2) if primary_box else 0.5
        subject_y = primary_box[1] if primary_box else 0.05
        aspect = width / max(height, 1)
        target_aspect = "1:1" if 0.9 <= aspect <= 1.1 else "4:5"
        mean_luminance = _mean_luminance(pixels)
        contrast = _histogram_contrast(pixels)
        return {
            "analysisVersion": ANALYSIS_VERSION,
            "analysis": {
                "peopleCount": sum(subject["role"] == "person" for subject in subjects),
                "subjects": subjects,
                "cameraHeight": "unknown",
                "horizon": round(horizon, 4),
                "tilt": 0.0,
                "backgroundRatio": round(max(0.0, 1.0 - subject_scale * (primary_box[2] if primary_box else 0.0)), 4),
                "aspectRatio": target_aspect,
                "palette": palette,
                "colorTemperature": color_target["colorTemperature"],
                "luminanceHistogram": _luminance_histogram(pixels),
                "poseConfidence": round(max((subject.get("pose", {}).get("confidence", 0.0) for subject in subjects), default=0.0), 4),
            },
            "targetComposition": {
                "targetAspectRatio": target_aspect,
                "layoutSlots": slots,
                "subjectScaleRange": [round(max(0.2, subject_scale * 0.8), 4), round(min(0.8, subject_scale * 1.2), 4)],
                "subjectPosition": _subject_position(subject_x),
                "headroomRange": [round(max(0.02, subject_y * 0.8), 4), round(min(0.3, subject_y * 1.2 + 0.02), 4)],
                "horizonPosition": round(horizon, 4),
                "cameraPitchRange": [-5, 5],
                "posePattern": "portrait" if any(subject["role"] == "person" for subject in subjects) else "static",
                "backgroundRatio": [round(max(0.0, 1.0 - subject_scale), 4), 0.85],
            },
            "colorTarget": color_target,
            "capabilities": {"composition": composition_available, "color": True},
        }

    def _model_subjects(self, image: Image.Image) -> list[dict[str, Any]]:
        try:
            result = self.segmenter(image, verbose=False)[0]
            names = result.names
            boxes = result.boxes
            masks = result.masks.xy if result.masks is not None else []
            candidates: list[dict[str, Any]] = []
            for index, box in enumerate(boxes):
                confidence = float(box.conf[0])
                if confidence < 0.35:
                    continue
                label = str(names[int(box.cls[0])]).lower()
                x1, y1, x2, y2 = [float(value) for value in box.xyxy[0]]
                bounds = [
                    max(0.0, min(1.0, x1 / image.width)),
                    max(0.0, min(1.0, y1 / image.height)),
                    max(0.0, min(1.0, (x2 - x1) / image.width)),
                    max(0.0, min(1.0, (y2 - y1) / image.height)),
                ]
                if bounds[2] * bounds[3] < 0.003 or bounds[2] * bounds[3] > 0.85:
                    continue
                role = "person" if label == "person" else "object"
                subject = {
                    "role": role,
                    "sourceLabel": label,
                    "bbox": [round(value, 4) for value in bounds],
                    "confidence": round(confidence, 4),
                    "visualKind": "person_silhouette" if role == "person" else "generic_object",
                }
                if index < len(masks):
                    subject["maskAvailable"] = True
                candidates.append(subject)

            # Segmentation supplies the object candidates. Pose and face are
            # separate signals: they enrich the person candidate and may
            # recover a person whose body is partly outside the segmentation
            # result. Neither signal is used to invent a non-person object.
            pose_candidates = self._pose_candidates(image)
            for subject in candidates:
                if subject["role"] != "person":
                    continue
                match = _best_bbox_match(subject["bbox"], pose_candidates)
                if match is not None:
                    subject["pose"] = match["pose"]

            face_candidates = self._face_candidates(image)
            people = [candidate for candidate in candidates if candidate["role"] == "person"]
            if people and face_candidates:
                match = _best_bbox_match(people[0]["bbox"], face_candidates)
                if match is not None:
                    people[0]["faceBbox"] = match["bbox"]
                    people[0]["faceConfidence"] = match["confidence"]
            elif not people and face_candidates:
                # Face-only references still provide a valid portrait target;
                # use the largest detected face as a conservative person slot.
                face = max(face_candidates, key=lambda item: item["bbox"][2] * item["bbox"][3])
                candidates.append({
                    "role": "person",
                    "sourceLabel": "face",
                    "bbox": face["bbox"],
                    "confidence": face["confidence"],
                    "faceBbox": face["bbox"],
                    "faceConfidence": face["confidence"],
                    "visualKind": "person_silhouette",
                    "pose": {"confidence": 0.0, "keypoints": []},
                })

            candidates.sort(key=lambda item: (item["role"] == "person", item["confidence"]), reverse=True)
            people = [candidate for candidate in candidates if candidate["role"] == "person"][:1]
            objects = [candidate for candidate in candidates if candidate["role"] != "person"]
            return people + _dedupe_subjects(objects)[:3]
        except Exception:
            logger.warning("reference_segmentation_failed", exc_info=True)
            return []

    def _pose_candidates(self, image: Image.Image) -> list[dict[str, Any]]:
        if self.pose_model is None:
            return []
        try:
            result = self.pose_model(image, verbose=False)[0]
            boxes = result.boxes
            keypoints = getattr(result, "keypoints", None)
            xy_values = _value_list(getattr(keypoints, "xy", None)) if keypoints is not None else []
            confidence_values = _value_list(getattr(keypoints, "conf", None)) if keypoints is not None else []
            candidates: list[dict[str, Any]] = []
            for index, box in enumerate(boxes):
                x1, y1, x2, y2 = [float(value) for value in box.xyxy[0]]
                bbox = _normalized_bbox(x1, y1, x2, y2, image.width, image.height)
                if bbox is None:
                    continue
                points = xy_values[index] if index < len(xy_values) else []
                point_conf = confidence_values[index] if index < len(confidence_values) else []
                normalized_points = []
                for point_index, point in enumerate(points):
                    if len(point) < 2:
                        continue
                    confidence = float(point_conf[point_index]) if point_index < len(point_conf) else 0.0
                    normalized_points.append([
                        round(max(0.0, min(1.0, float(point[0]) / image.width)), 4),
                        round(max(0.0, min(1.0, float(point[1]) / image.height)), 4),
                        round(max(0.0, min(1.0, confidence)), 4),
                    ])
                candidates.append({
                    "bbox": bbox,
                    "pose": {
                        "confidence": round(sum(point[2] for point in normalized_points) / len(normalized_points), 4)
                        if normalized_points else 0.0,
                        "keypoints": normalized_points,
                    },
                })
            return candidates
        except Exception:
            logger.warning("reference_pose_failed", exc_info=True)
            return []

    def _face_candidates(self, image: Image.Image) -> list[dict[str, Any]]:
        if self.face_model is None:
            return []
        try:
            import numpy as np

            # InsightFace expects BGR ndarray input, unlike Ultralytics' PIL
            # path. Keep this conversion in the optional face branch so color
            # analysis remains dependency-light when face detection is absent.
            bgr = np.asarray(image)[:, :, ::-1].copy()
            faces = self.face_model.get(bgr)
            candidates: list[dict[str, Any]] = []
            for face in faces:
                raw_bbox = getattr(face, "bbox", None)
                if raw_bbox is None or len(raw_bbox) < 4:
                    continue
                bbox = _normalized_bbox(
                    float(raw_bbox[0]), float(raw_bbox[1]), float(raw_bbox[2]), float(raw_bbox[3]),
                    image.width, image.height,
                )
                if bbox is None:
                    continue
                candidates.append({
                    "bbox": bbox,
                    "confidence": round(float(getattr(face, "det_score", 0.0)), 4),
                })
            return candidates
        except Exception:
            logger.warning("reference_face_failed", exc_info=True)
            return []


def _value_list(value: Any) -> list[Any]:
    if value is None:
        return []
    try:
        value = value.detach().cpu()
    except AttributeError:
        pass
    try:
        return value.tolist()
    except AttributeError:
        return list(value)


def _normalized_bbox(x1: float, y1: float, x2: float, y2: float, width: int, height: int) -> list[float] | None:
    if width <= 0 or height <= 0 or x2 <= x1 or y2 <= y1:
        return None
    bbox = [
        max(0.0, min(1.0, x1 / width)),
        max(0.0, min(1.0, y1 / height)),
        max(0.0, min(1.0, (x2 - x1) / width)),
        max(0.0, min(1.0, (y2 - y1) / height)),
    ]
    area = bbox[2] * bbox[3]
    return bbox if 0.003 <= area <= 0.85 else None


def _best_bbox_match(target: list[float], candidates: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not candidates:
        return None
    return max(candidates, key=lambda item: _iou(target, item["bbox"]))


def _slot_for_subject(subject: dict[str, Any]) -> dict[str, Any]:
    return {
        "role": subject["role"],
        "visualKind": subject["visualKind"],
        "bounds": subject["bbox"],
        "semanticHint": subject.get("sourceLabel"),
    }


def _dedupe_subjects(subjects: list[dict[str, Any]]) -> list[dict[str, Any]]:
    kept: list[dict[str, Any]] = []
    for candidate in subjects:
        if any(_iou(candidate["bbox"], other["bbox"]) >= 0.55 for other in kept):
            continue
        kept.append(candidate)
    return kept


def _iou(a: list[float], b: list[float]) -> float:
    ax1, ay1, aw, ah = a
    bx1, by1, bw, bh = b
    left, top = max(ax1, bx1), max(ay1, by1)
    right, bottom = min(ax1 + aw, bx1 + bw), min(ay1 + ah, by1 + bh)
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    union = aw * ah + bw * bh - intersection
    return intersection / union if union > 0 else 0.0


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


def _color_target(pixels: list[tuple[int, int, int]], palette: list[str]) -> dict[str, Any]:
    average = tuple(sum(channel[index] for channel in pixels) / max(len(pixels), 1) for index in range(3))
    contrast = _histogram_contrast(pixels)
    mean_luminance = _mean_luminance(pixels)
    return {
        "palette": palette,
        "colorTemperature": int(max(3000, min(7500, 5200 + (average[0] - average[2]) * 12))),
        "exposureBias": round((0.5 - mean_luminance) * 1.2, 4),
        "contrast": round((contrast - 0.18) * 1.8, 4),
        "saturation": round(_saturation_bias(pixels), 4),
        "fade": round(max(0.0, 0.25 - contrast), 4),
        "grain": 0.08 if contrast < 0.16 else 0.02,
        "vignette": 0.08,
    }


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
