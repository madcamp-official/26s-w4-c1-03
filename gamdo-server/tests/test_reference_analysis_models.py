from __future__ import annotations

import io

import pytest
from PIL import Image

from app.reference_analysis import ReferenceAnalyzer

pytest.importorskip("numpy")


class FakeBox:
    def __init__(self, xyxy: list[float], confidence: float, label: int = 0):
        self.xyxy = [xyxy]
        self.conf = [confidence]
        self.cls = [label]


class FakeBoxes(list):
    pass


class FakeMasks:
    xy = [[[10.0, 10.0], [30.0, 10.0], [30.0, 40.0], [10.0, 40.0]]]


class FakeSegmentation:
    def __call__(self, image, verbose=False):
        result = type("Result", (), {})()
        result.names = {0: "person", 1: "cup"}
        result.boxes = FakeBoxes([
            FakeBox([10.0, 10.0, 55.0, 90.0], 0.95, 0),
            FakeBox([65.0, 35.0, 90.0, 70.0], 0.88, 1),
        ])
        result.masks = FakeMasks()
        return [result]


class FakeKeypoints:
    xy = [[
        [30.0, 20.0],
        [20.0, 40.0],
        [40.0, 40.0],
    ]]
    conf = [[0.95, 0.90, 0.85]]


class FakePose:
    def __call__(self, image, verbose=False):
        result = type("Result", (), {})()
        result.boxes = FakeBoxes([FakeBox([12.0, 12.0, 54.0, 89.0], 0.91)])
        result.keypoints = FakeKeypoints()
        return [result]


class FakeFace:
    bbox = [18.0, 14.0, 43.0, 39.0]
    det_score = 0.97


class FakeFaceAnalysis:
    def get(self, image):
        return [FakeFace()]


def image_bytes() -> bytes:
    image = Image.new("RGB", (100, 100), (120, 120, 120))
    output = io.BytesIO()
    image.save(output, format="JPEG")
    return output.getvalue()


def test_gpu_model_outputs_enrich_reference_slots() -> None:
    analyzer = ReferenceAnalyzer(
        segmenter=FakeSegmentation(),
        pose_model=FakePose(),
        face_model=FakeFaceAnalysis(),
    )

    payload = analyzer.analyze(image_bytes())
    subjects = payload["analysis"]["subjects"]
    person = subjects[0]

    assert payload["capabilities"] == {"composition": True, "color": True}
    assert payload["analysis"]["peopleCount"] == 1
    assert len(subjects) == 2
    assert person["pose"]["confidence"] > 0.8
    assert len(person["pose"]["keypoints"]) == 3
    assert person["faceConfidence"] == 0.97
    assert person["faceBbox"] == [0.18, 0.14, 0.25, 0.25]
    assert payload["targetComposition"]["layoutSlots"][0]["role"] == "person"
