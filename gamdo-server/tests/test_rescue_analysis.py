from __future__ import annotations

import io

import pytest
from PIL import ExifTags, Image

from app.rescue_analysis import analyze_rescue


ORIENTATION_TAG = ExifTags.Base.Orientation


def _upright_image(width: int = 300, height: int = 400) -> Image.Image:
    return Image.new("RGB", (width, height), (30, 30, 30))


def _jpeg_bytes(image: Image.Image, *, orientation: int | None = None) -> bytes:
    exif = image.getexif()
    if orientation is not None:
        exif[ORIENTATION_TAG] = orientation
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=90, exif=exif)
    return buffer.getvalue()


def _landscape_sensor_bytes(upright: Image.Image, *, orientation: int = 6) -> bytes:
    """Same construction as tests/test_reference_analysis.py: sensor pixels
    rotated 90 degrees from how the photo should display, plus the
    Orientation tag that says so.
    """
    raw = upright.transpose(Image.Transpose.ROTATE_90)
    return _jpeg_bytes(raw, orientation=orientation)


def test_analyze_rescue_reports_upright_dimensions_for_oriented_image() -> None:
    """A portrait phone photo (300x400 as displayed) stored as a 400x300
    landscape sensor capture plus Orientation=6 must be reported back with
    its display dimensions. rescue_analysis.py reads width/height through its
    own separate Image.open() call (line 27), independent of the analyzer
    reused below it -- both must agree on the same, correctly oriented frame.
    """
    upright = _upright_image(300, 400)
    payload = _landscape_sensor_bytes(upright)

    result = analyze_rescue(payload)

    assert result["image"] == {"width": 300, "height": 400}


def test_analyze_rescue_survives_a_corrupt_orientation_tag(monkeypatch: pytest.MonkeyPatch) -> None:
    """A malformed Orientation value must not turn into a 500 for the caller
    (same posture as storage.save_exif_stripped_input, O-8): log and continue
    with the pixels as decoded.
    """
    import app.rescue_analysis as rescue_analysis_module

    def _boom(*_args, **_kwargs):
        raise ValueError("simulated malformed orientation tag")

    monkeypatch.setattr(rescue_analysis_module.ImageOps, "exif_transpose", _boom)
    upright = _upright_image(300, 400)
    payload = _landscape_sensor_bytes(upright)

    result = analyze_rescue(payload)

    assert result["analysisVersion"] == 2


def test_generation_capabilities_follow_deployment_readiness(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("GAMDO_COMFYUI_URL", raising=False)
    monkeypatch.delenv("GAMDO_RELIGHT_URL", raising=False)
    monkeypatch.delenv("GAMDO_VIEWPOINT_URL", raising=False)
    result = analyze_rescue(_jpeg_bytes(_upright_image()))
    assert result["capabilities"]["outpaint"] is False
    assert result["capabilities"]["relight"] is False
    assert result["capabilities"]["viewpoint"] is False


def test_backlight_diagnostic_detects_bright_border_and_dark_subject() -> None:
    image = Image.new("RGB", (200, 200), "white")
    for y in range(45, 170):
        for x in range(45, 155):
            image.putpixel((x, y), (20, 20, 20))
    result = analyze_rescue(_jpeg_bytes(image))
    assert result["diagnostics"]["lighting"]["backlightScore"] >= 0.18
