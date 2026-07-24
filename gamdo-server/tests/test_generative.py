from __future__ import annotations

from pathlib import Path

from PIL import Image

from app.generative import CandidateValidator, GeneratedCandidate


class SameIdentity:
    def verify(self, original: Image.Image, candidate: Image.Image) -> bool:
        return True


def test_candidate_validator_requires_identity_verifier(tmp_path: Path) -> None:
    original = tmp_path / "original.png"
    candidate = tmp_path / "candidate.png"
    Image.new("RGB", (16, 16), (120, 140, 120)).save(original)
    Image.new("RGB", (16, 16), (120, 140, 120)).save(candidate)

    result = CandidateValidator().validate(original, GeneratedCandidate(candidate, 1))

    assert result.passed is False
    assert result.reason == "face_identity_unverified"


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
