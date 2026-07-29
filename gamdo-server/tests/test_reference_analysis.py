from __future__ import annotations

import io

import pytest
from PIL import ExifTags, Image

from app.reference_analysis import analyze_reference
from app.storage import strip_gps_exif


ORIENTATION_TAG = ExifTags.Base.Orientation
GPS_TAG = ExifTags.Base.GPSInfo


def _upright_reference_image(width: int = 300, height: int = 400, band_rows: int = 60) -> Image.Image:
    """Ground-truth "as displayed" portrait image: a bright band across the
    top `band_rows` rows (e.g. sky/ceiling), dark below it. This is the shape
    a correct horizon/composition read should see for a portrait photo.
    """
    image = Image.new("RGB", (width, height), (10, 10, 10))
    for y in range(band_rows):
        for x in range(width):
            image.putpixel((x, y), (250, 250, 250))
    return image


def _jpeg_bytes(image: Image.Image, *, orientation: int | None = None, gps: bool = False) -> bytes:
    exif = image.getexif()
    if orientation is not None:
        exif[ORIENTATION_TAG] = orientation
    if gps:
        exif[GPS_TAG] = {1: "N", 2: (37.0, 33.0, 12.34), 3: "E", 4: (127.0, 1.0, 2.34)}
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=95, exif=exif)
    return buffer.getvalue()


def _landscape_sensor_bytes(upright: Image.Image, *, orientation: int = 6, gps: bool = False) -> bytes:
    """The bytes a phone actually stores for a portrait shot: sensor pixels
    rotated 90 degrees from how the photo should display, plus the
    Orientation tag that says so.

    `Image.Transpose.ROTATE_90` is the verified inverse of what
    `ImageOps.exif_transpose` applies for Orientation=6 (`Transpose.ROTATE_270`):
    composing the two is a 360-degree rotation, so this round-trips back to
    `upright` pixel-for-pixel once the fix under test applies the transpose.
    """
    raw = upright.transpose(Image.Transpose.ROTATE_90)
    return _jpeg_bytes(raw, orientation=orientation, gps=gps)


def test_analyze_reference_horizon_matches_upright_interpretation_for_oriented_image() -> None:
    """A portrait phone photo is stored as landscape sensor pixels plus an
    Orientation tag. The analyzer must measure the photo as it will actually
    be displayed, not lying on its side as the sensor happened to store it.
    """
    upright = _upright_reference_image()
    expected = analyze_reference(_jpeg_bytes(upright))

    oriented_result = analyze_reference(_landscape_sensor_bytes(upright))

    assert oriented_result["analysis"]["horizon"] == pytest.approx(expected["analysis"]["horizon"], abs=1e-3)
    assert oriented_result["targetComposition"]["horizonPosition"] == pytest.approx(
        expected["targetComposition"]["horizonPosition"], abs=1e-3
    )


def test_analyze_reference_horizon_uses_the_upright_band_position() -> None:
    """Pinned, human-checkable number: the strongest luminance transition
    sits at row 60 of the 400-tall upright image, i.e. 60/400 = 0.15.
    """
    upright = _upright_reference_image(width=300, height=400, band_rows=60)

    result = analyze_reference(_landscape_sensor_bytes(upright))

    assert result["analysis"]["horizon"] == pytest.approx(0.15, abs=0.01)


def test_analyze_reference_after_gps_strip_still_honors_orientation() -> None:
    """strip_gps_exif runs first on every upload route, before analyze_reference
    ever sees the bytes. Confirm the two compose correctly end-to-end: GPS
    stripping's JPEG re-save must not drop the Orientation tag the transpose
    depends on.
    """
    upright = _upright_reference_image()
    payload = _landscape_sensor_bytes(upright, gps=True)

    stripped = strip_gps_exif(payload)
    result = analyze_reference(stripped)

    assert result["analysis"]["horizon"] == pytest.approx(0.15, abs=0.01)


def test_analyze_reference_survives_a_corrupt_orientation_tag(monkeypatch: pytest.MonkeyPatch) -> None:
    """A malformed Orientation value must not turn into a 500 for the caller
    (same posture as storage.save_exif_stripped_input, O-8): log and continue
    with the pixels as decoded.
    """
    import app.reference_analysis as reference_analysis_module

    def _boom(*_args, **_kwargs):
        raise ValueError("simulated malformed orientation tag")

    monkeypatch.setattr(reference_analysis_module.ImageOps, "exif_transpose", _boom)
    upright = _upright_reference_image()
    payload = _landscape_sensor_bytes(upright)

    result = analyze_reference(payload)

    assert result["analysisVersion"] == 3
