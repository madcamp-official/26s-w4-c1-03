from __future__ import annotations

import io

from PIL import Image, ExifTags

from app.storage import save_exif_stripped_input, strip_gps_exif


GPS_TAG = ExifTags.Base.GPSInfo
ORIENTATION_TAG = ExifTags.Base.Orientation
MAKE_TAG = ExifTags.Base.Make


def _gps_ifd() -> dict[int, object]:
    # A real-looking GPS IFD: latitude/longitude refs and DMS rationals, the
    # same shape a phone camera writes.
    return {1: "N", 2: (37.0, 33.0, 12.34), 3: "E", 4: (127.0, 1.0, 2.34)}


def _asymmetric_image(size: tuple[int, int] = (30, 10)) -> Image.Image:
    """An image where each quadrant is a distinct color, so a transpose is verifiable."""
    width, height = size
    image = Image.new("RGB", size, (0, 0, 0))
    for x in range(width // 3):
        for y in range(height):
            image.putpixel((x, y), (255, 0, 0))  # red block on the left edge
    return image


def _jpeg_bytes(
    *,
    orientation: int | None = None,
    gps: bool = False,
    make: str | None = "GamdoTestCam",
    quality: int = 90,
    image: Image.Image | None = None,
) -> bytes:
    image = image if image is not None else _asymmetric_image()
    exif = image.getexif()
    if orientation is not None:
        exif[ORIENTATION_TAG] = orientation
    if make is not None:
        exif[MAKE_TAG] = make
    if gps:
        exif[GPS_TAG] = _gps_ifd()
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=quality, exif=exif)
    return buffer.getvalue()


# --- strip_gps_exif ---------------------------------------------------------


def test_strip_gps_exif_removes_gps_but_keeps_orientation_and_camera_tags() -> None:
    payload = _jpeg_bytes(orientation=6, gps=True)

    stripped = strip_gps_exif(payload)

    reopened = Image.open(io.BytesIO(stripped))
    exif = reopened.getexif()
    assert GPS_TAG not in exif
    assert exif.get_ifd(ExifTags.IFD.GPSInfo) == {}
    assert exif.get(ORIENTATION_TAG) == 6
    assert exif.get(MAKE_TAG) == "GamdoTestCam"


def test_strip_gps_exif_leaves_no_trace_of_the_coordinates_in_the_bytes() -> None:
    payload = _jpeg_bytes(gps=True)

    stripped = strip_gps_exif(payload)

    # Belt-and-suspenders: not just "the tag lookup returns nothing" but the
    # encoded degree values are gone from the byte stream entirely.
    import struct

    def has_int(data: bytes, value: int) -> bool:
        return any(struct.pack(fmt, value) in data for fmt in ("<I", ">I"))

    assert has_int(payload, 37)  # latitude degrees was present in the source
    assert not has_int(stripped, 37)


def test_strip_gps_exif_is_a_noop_without_gps() -> None:
    payload = _jpeg_bytes(orientation=1, gps=False)

    stripped = strip_gps_exif(payload)

    # No GPS tag to remove -> return the exact original bytes, no re-encode.
    assert stripped == payload


def test_strip_gps_exif_survives_corrupt_bytes_without_raising() -> None:
    garbage = b"\xff\xd8\xff\xe0not a real jpeg" * 4

    result = strip_gps_exif(garbage)

    assert result == garbage


def test_strip_gps_exif_survives_non_image_bytes_without_raising() -> None:
    not_an_image = b"hello world, this is definitely not an image"

    result = strip_gps_exif(not_an_image)

    assert result == not_an_image


def test_strip_gps_exif_survives_empty_bytes_without_raising() -> None:
    assert strip_gps_exif(b"") == b""


def test_strip_gps_exif_handles_png() -> None:
    image = Image.new("RGB", (12, 8), (10, 20, 30))
    exif = image.getexif()
    exif[ORIENTATION_TAG] = 3
    exif[GPS_TAG] = _gps_ifd()
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", exif=exif)
    payload = buffer.getvalue()

    stripped = strip_gps_exif(payload)

    reopened = Image.open(io.BytesIO(stripped))
    result_exif = reopened.getexif()
    assert GPS_TAG not in result_exif
    assert result_exif.get(ORIENTATION_TAG) == 3


def test_strip_gps_exif_handles_webp() -> None:
    image = Image.new("RGB", (12, 8), (10, 20, 30))
    exif = image.getexif()
    exif[ORIENTATION_TAG] = 8
    exif[GPS_TAG] = _gps_ifd()
    buffer = io.BytesIO()
    image.save(buffer, format="WEBP", exif=exif)
    payload = buffer.getvalue()

    stripped = strip_gps_exif(payload)

    reopened = Image.open(io.BytesIO(stripped))
    result_exif = reopened.getexif()
    assert GPS_TAG not in result_exif
    assert result_exif.get(ORIENTATION_TAG) == 8


def test_strip_gps_exif_keeps_jpeg_quality_close_to_source() -> None:
    payload = _jpeg_bytes(gps=True, quality=90)

    stripped = strip_gps_exif(payload)

    # quality="keep" should avoid falling back to Pillow's default quality
    # (75), which would recompress harder and shrink the file noticeably more
    # than removing ~20-40 bytes of EXIF would on its own.
    assert len(stripped) >= len(payload) * 0.85


# --- save_exif_stripped_input (edit-jobs persisted copy) --------------------


def test_save_exif_stripped_input_drops_gps_and_all_other_exif(tmp_path, monkeypatch) -> None:
    import app.storage as storage_module

    monkeypatch.setattr(storage_module, "INPUT_DIR", tmp_path / "inputs")
    payload = _jpeg_bytes(gps=True, orientation=1)

    storage_path, bytes_count = save_exif_stripped_input("job_test_gps", payload)

    saved = (tmp_path / "inputs" / "job_test_gps.png")
    assert saved.exists()
    assert bytes_count == saved.stat().st_size
    reopened = Image.open(saved)
    assert not reopened.getexif()  # still a full strip for the persisted copy


def test_save_exif_stripped_input_applies_orientation_before_dropping_exif(tmp_path, monkeypatch) -> None:
    import app.storage as storage_module

    monkeypatch.setattr(storage_module, "INPUT_DIR", tmp_path / "inputs")
    source = _asymmetric_image((30, 10))
    payload = _jpeg_bytes(orientation=6, gps=True, image=source)

    storage_path, _ = save_exif_stripped_input("job_test_orientation", payload)

    saved_path = tmp_path / "inputs" / "job_test_orientation.png"
    result = Image.open(saved_path)

    # Orientation 6 means the stored pixels are rotated 90 degrees from how
    # the photo should display; a correct transpose swaps width/height (the
    # source canvas was 30x10) and moves the left-edge red block to the top
    # edge, matching what ImageOps.exif_transpose produces (verified
    # separately against this exact fixture).
    assert result.size == (10, 30)
    assert result.getpixel((0, 0))[0] > 200  # red survived at the new top-left
    assert result.getpixel((result.width - 1, 0))[0] > 200  # red spans the new top row
    assert sum(result.getpixel((0, result.height - 1))) < 30  # bottom stayed black
    assert not result.getexif()


def test_save_exif_stripped_input_without_orientation_tag_is_unrotated(tmp_path, monkeypatch) -> None:
    import app.storage as storage_module

    monkeypatch.setattr(storage_module, "INPUT_DIR", tmp_path / "inputs")
    payload = _jpeg_bytes(orientation=None, gps=False, image=_asymmetric_image((30, 10)))

    save_exif_stripped_input("job_test_no_orientation", payload)

    result = Image.open(tmp_path / "inputs" / "job_test_no_orientation.png")
    assert result.size == (30, 10)


def test_save_exif_stripped_input_survives_a_corrupt_orientation_tag(tmp_path, monkeypatch) -> None:
    """A malformed Orientation value must not turn into a 500 for the caller.

    save_exif_stripped_input() is expected to keep working (raising only on
    genuinely undecodable image bytes, which the route layer turns into a 415)
    even if orientation correction itself cannot be applied.
    """
    import app.storage as storage_module

    monkeypatch.setattr(storage_module, "INPUT_DIR", tmp_path / "inputs")

    def _boom(*_args, **_kwargs):
        raise ValueError("simulated malformed orientation tag")

    monkeypatch.setattr(storage_module.ImageOps, "exif_transpose", _boom)
    payload = _jpeg_bytes(orientation=6, gps=True)

    storage_path, bytes_count = save_exif_stripped_input("job_test_bad_orientation", payload)

    assert (tmp_path / "inputs" / "job_test_bad_orientation.png").exists()
    assert bytes_count > 0
