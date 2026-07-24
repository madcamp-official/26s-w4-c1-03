from __future__ import annotations

import hashlib
import json
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


def candidate_id(job_id: str, candidate: GeneratedCandidate) -> str:
    digest = hashlib.sha256(f"{job_id}:{candidate.seed}:{candidate.path}".encode()).hexdigest()[:16]
    return f"result_{digest}"


def _histogram_distance(original: Image.Image, generated: Image.Image) -> float:
    left = ImageStat.Stat(original.convert("RGB").resize((32, 32))).mean
    right = ImageStat.Stat(generated.convert("RGB").resize((32, 32))).mean
    return sum(abs(a - b) for a, b in zip(left, right)) / (255.0 * 3.0)
