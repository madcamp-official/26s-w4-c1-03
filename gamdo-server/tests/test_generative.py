from __future__ import annotations

from pathlib import Path

from PIL import Image

from app.generative import CandidateValidator, GeneratedCandidate


class SameIdentity:
    def verify(self, original: Image.Image, candidate: Image.Image) -> bool:
        return True


class FaceMaskGuard:
    def __init__(self, intersects: bool, same_face_count: bool = True) -> None:
        self.intersects = intersects
        self.same_face_count = same_face_count

    def verify(self, original: Image.Image, candidate: Image.Image) -> bool:
        return True

    def mask_intersects_face(self, original: Image.Image, operations: list[dict]) -> bool:
        return self.intersects

    def face_count_matches(self, original: Image.Image, candidate: Image.Image) -> bool:
        return self.same_face_count


def test_candidate_validator_keeps_candidate_when_identity_is_unavailable(tmp_path: Path) -> None:
    original = tmp_path / "original.png"
    candidate = tmp_path / "candidate.png"
    Image.new("RGB", (16, 16), (120, 140, 120)).save(original)
    Image.new("RGB", (16, 16), (120, 140, 120)).save(candidate)

    result = CandidateValidator().validate(original, GeneratedCandidate(candidate, 1))

    assert result.passed is True
    assert "face_identity_unverified" in result.validation["qualityWarnings"]


def test_candidate_validator_rejects_input_path_alias(tmp_path: Path) -> None:
    original = tmp_path / "original.png"
    Image.new("RGB", (16, 16), (120, 140, 120)).save(original)

    result = CandidateValidator(SameIdentity()).validate(
        original, GeneratedCandidate(original, 99)
    )

    assert result.passed is False
    assert result.reason == "candidate_aliases_input"


def test_candidate_validator_accepts_only_verified_matching_candidate(tmp_path: Path) -> None:
    original = tmp_path / "original.png"
    candidate = tmp_path / "candidate.png"
    Image.new("RGB", (16, 16), (120, 140, 120)).save(original)
    Image.new("RGB", (16, 16), (125, 140, 118)).save(candidate)

    result = CandidateValidator(SameIdentity()).validate(
        original, GeneratedCandidate(candidate, 2)
    )

    assert result.passed is True
    assert result.validation["identityVerified"] is True


def test_candidate_validator_rejects_mask_over_protected_face(tmp_path: Path) -> None:
    original = tmp_path / "original.png"
    Image.new("RGB", (16, 16), (120, 140, 120)).save(original)

    validator = CandidateValidator(FaceMaskGuard(intersects=True))

    assert validator.mask_is_safe(
        original,
        [{"type": "remove_objects", "masks": [{"rect": {"x": 0.2, "y": 0.2, "width": 0.2, "height": 0.2}}]}],
    ) is False


def test_candidate_validator_allows_mask_away_from_face(tmp_path: Path) -> None:
    original = tmp_path / "original.png"
    Image.new("RGB", (16, 16), (120, 140, 120)).save(original)

    validator = CandidateValidator(FaceMaskGuard(intersects=False))

    assert validator.mask_is_safe(
        original,
        [{"type": "remove_objects", "masks": [{"rect": {"x": 0.8, "y": 0.8, "width": 0.1, "height": 0.1}}]}],
    ) is True


def test_candidate_validator_reports_changed_face_count_as_quality_signal(tmp_path: Path) -> None:
    original = tmp_path / "original.png"
    candidate = tmp_path / "candidate.png"
    Image.new("RGB", (16, 16), (120, 140, 120)).save(original)
    Image.new("RGB", (16, 16), (125, 140, 118)).save(candidate)

    result = CandidateValidator(FaceMaskGuard(False, same_face_count=False)).validate(
        original, GeneratedCandidate(candidate, 3)
    )

    assert result.passed is True
    assert "face_count_changed" in result.validation["qualityWarnings"]


def test_candidate_validator_allows_bounded_outpaint_with_original_interior(tmp_path: Path) -> None:
    original = tmp_path / "original.png"
    candidate = tmp_path / "candidate.png"
    Image.new("RGB", (20, 16), (120, 140, 120)).save(original)
    output = Image.new("RGB", (23, 16), (120, 140, 120))
    output.save(candidate)

    result = CandidateValidator(SameIdentity()).validate(
        original,
        GeneratedCandidate(candidate, 4, operation="outpaint"),
        [{"type": "outpaint", "direction": "right", "ratio": 0.15}],
    )

    assert result.passed is True
