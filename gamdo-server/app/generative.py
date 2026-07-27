from __future__ import annotations

import hashlib
import math
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from PIL import Image, ImageStat


class ProviderNotReady(RuntimeError):
    pass


@dataclass(frozen=True)
class GeneratedCandidate:
    path: Path
    seed: int
    operation: str = "remove_objects"


class GenerativeEditProvider(Protocol):
    def remove_objects(
        self,
        image_path: Path,
        operations: list[dict[str, Any]],
        result_count: int,
    ) -> list[GeneratedCandidate]:
        """Return generated candidates; never mutate the original input."""


class UnavailableProvider:
    def remove_objects(
        self,
        image_path: Path,
        operations: list[dict[str, Any]],
        result_count: int,
    ) -> list[GeneratedCandidate]:
        raise ProviderNotReady("generative provider is not configured")


class IdentityVerifier(Protocol):
    def verify(self, original: Image.Image, candidate: Image.Image) -> bool:
        """Compare identity in memory; implementations must not persist embeddings."""


class UnavailableIdentityVerifier:
    def verify(self, original: Image.Image, candidate: Image.Image) -> bool:
        return False


class InsightFaceVerifier:
    """In-memory face identity check backed by InsightFace when enabled.

    The model is loaded once per worker, while face embeddings are kept only for
    the duration of ``verify`` and are never serialized.
    """

    DEFAULT_THRESHOLD = 0.35

    def __init__(self, threshold: float = DEFAULT_THRESHOLD) -> None:
        self.threshold = threshold if 0.0 <= threshold <= 1.0 and math.isfinite(threshold) else self.DEFAULT_THRESHOLD
        self._app: Any | None = None

    @classmethod
    def from_environment(cls) -> "InsightFaceVerifier | None":
        if os.getenv("GAMDO_INSIGHTFACE_ENABLED", "0") != "1":
            return None
        try:
            return cls(float(os.getenv("GAMDO_FACE_SIMILARITY_THRESHOLD", str(cls.DEFAULT_THRESHOLD))))
        except ValueError:
            return cls()

    def verify(self, original: Image.Image, candidate: Image.Image) -> bool:
        similarity = self.similarity(original, candidate)
        return similarity is not None and similarity >= self.threshold

    def similarity(self, original: Image.Image, candidate: Image.Image) -> float | None:
        """Return the in-memory cosine similarity, or ``None`` when unverifiable.

        This is exposed for offline threshold calibration only. Embeddings are
        still created and discarded inside this call; no face vector is returned
        or persisted.
        """
        try:
            import numpy as np
            from insightface.app import FaceAnalysis

            left = self._largest_face(self._faces(original, FaceAnalysis, np))
            right = self._largest_face(self._faces(candidate, FaceAnalysis, np))
            if left is None or right is None:
                return None
            return float(np.dot(left.normed_embedding, right.normed_embedding))
        except (ImportError, OSError, RuntimeError, ValueError):
            return None

    def mask_intersects_face(
        self,
        original: Image.Image,
        operations: list[dict[str, Any]],
        margin_ratio: float = 0.10,
    ) -> bool:
        """Return whether a requested normalized edit mask touches a face.

        Face boxes and embeddings stay in memory. A positive result is used by
        the worker to reject the generative operation and preserve the face.
        """
        try:
            import numpy as np
            from insightface.app import FaceAnalysis

            faces = self._faces(original, FaceAnalysis, np)
            if not faces:
                return False
            image_width, image_height = original.size
            for face in faces:
                x1, y1, x2, y2 = (float(value) for value in face.bbox)
                face_width = max(0.0, x2 - x1) / image_width
                face_height = max(0.0, y2 - y1) / image_height
                protected = (
                    max(0.0, x1 / image_width - face_width * margin_ratio),
                    max(0.0, y1 / image_height - face_height * margin_ratio),
                    min(1.0, x2 / image_width + face_width * margin_ratio),
                    min(1.0, y2 / image_height + face_height * margin_ratio),
                )
                for operation in operations:
                    for mask in operation.get("masks", []):
                        rect = mask.get("rect", mask) if isinstance(mask, dict) else {}
                        if _rectangles_intersect(protected, _normalized_rect(rect)):
                            return True
            return False
        except (ImportError, OSError, RuntimeError, ValueError, TypeError, AttributeError):
            # An unavailable detector is handled by the normal candidate
            # validator, which fails closed when identity cannot be verified.
            return False

    def face_count_matches(self, original: Image.Image, candidate: Image.Image) -> bool:
        """Reject candidates that add or remove detected faces."""
        try:
            import numpy as np
            from insightface.app import FaceAnalysis

            return len(self._faces(original, FaceAnalysis, np)) == len(
                self._faces(candidate, FaceAnalysis, np)
            )
        except (ImportError, OSError, RuntimeError, ValueError, TypeError, AttributeError):
            return False

    def _faces(self, image: Image.Image, face_analysis: Any, numpy: Any) -> list[Any]:
        if self._app is None:
            providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
            self._app = face_analysis(name="buffalo_l", providers=providers)
            self._app.prepare(ctx_id=0, det_size=(640, 640))
        return self._app.get(_bgr(image, numpy))

    @staticmethod
    def _largest_face(faces: list[Any]) -> Any | None:
        return max(faces, key=lambda face: float(face.bbox[2] - face.bbox[0]) * float(face.bbox[3] - face.bbox[1]), default=None)


def _bgr(image: Image.Image, numpy: Any) -> Any:
    return numpy.asarray(image.convert("RGB"))[:, :, ::-1].copy()


@dataclass(frozen=True)
class ValidationResult:
    passed: bool
    reason: str
    validation: dict[str, Any]


class CandidateValidator:
    def __init__(self, identity_verifier: IdentityVerifier | None = None) -> None:
        self.identity_verifier = identity_verifier or UnavailableIdentityVerifier()

    def validate(self, original_path: Path, candidate: GeneratedCandidate) -> ValidationResult:
        try:
            if candidate.path.resolve() == original_path.resolve():
                return ValidationResult(False, "candidate_aliases_input", {})
            with Image.open(original_path) as original, Image.open(candidate.path) as generated:
                original.load()
                generated.load()
                if original.size != generated.size:
                    return ValidationResult(False, "dimensions_changed", {})
                histogram_distance = _histogram_distance(original, generated)
                if histogram_distance > 0.85:
                    return ValidationResult(
                        False,
                        "extreme_color_change",
                        {"histogramDistance": histogram_distance},
                    )
                count_guard = getattr(self.identity_verifier, "face_count_matches", None)
                if count_guard is not None and not count_guard(original, generated):
                    return ValidationResult(
                        False,
                        "face_count_changed",
                        {"histogramDistance": histogram_distance},
                    )
                identity_ok = self.identity_verifier.verify(original, generated)
                if not identity_ok:
                    return ValidationResult(
                        False,
                        "face_identity_unverified",
                        {"histogramDistance": histogram_distance},
                    )
                return ValidationResult(
                    True,
                    "passed",
                    {"histogramDistance": histogram_distance, "identityVerified": True},
                )
        except (OSError, ValueError):
            return ValidationResult(False, "invalid_candidate", {})

    def mask_is_safe(self, original_path: Path, operations: list[dict[str, Any]]) -> bool:
        """Reject masks that overlap a protected face when the verifier supports it."""
        guard = getattr(self.identity_verifier, "mask_intersects_face", None)
        if guard is None:
            return True
        try:
            with Image.open(original_path) as original:
                original.load()
                return not bool(guard(original, operations))
        except (OSError, ValueError, TypeError):
            return False


def candidate_id(job_id: str, candidate: GeneratedCandidate) -> str:
    digest = hashlib.sha256(f"{job_id}:{candidate.seed}:{candidate.path}".encode()).hexdigest()[:16]
    return f"result_{digest}"


def _histogram_distance(original: Image.Image, generated: Image.Image) -> float:
    left = ImageStat.Stat(original.convert("RGB").resize((32, 32))).mean
    right = ImageStat.Stat(generated.convert("RGB").resize((32, 32))).mean
    return sum(abs(a - b) for a, b in zip(left, right)) / (255.0 * 3.0)


def _normalized_rect(rect: dict[str, Any]) -> tuple[float, float, float, float]:
    return (
        float(rect.get("x", 0.0)),
        float(rect.get("y", 0.0)),
        float(rect.get("x", 0.0)) + float(rect.get("width", 0.0)),
        float(rect.get("y", 0.0)) + float(rect.get("height", 0.0)),
    )


def _rectangles_intersect(
    left: tuple[float, float, float, float],
    right: tuple[float, float, float, float],
) -> bool:
    return left[0] < right[2] and right[0] < left[2] and left[1] < right[3] and right[1] < left[3]
