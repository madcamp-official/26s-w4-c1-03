from __future__ import annotations

import io
import logging
from pathlib import Path
from typing import Any

from PIL import ExifTags, Image, ImageOps


SERVER_ROOT = Path(__file__).resolve().parents[1]
INPUT_DIR = SERVER_ROOT / "storage" / "inputs"
RESULT_DIR = SERVER_ROOT / "storage" / "results"
TMP_DIR = SERVER_ROOT / "storage" / "tmp"
SHOOT_DIR = SERVER_ROOT / "storage" / "shoot"

logger = logging.getLogger(__name__)


def ensure_storage() -> None:
    for directory in (INPUT_DIR, RESULT_DIR, TMP_DIR, SHOOT_DIR):
        directory.mkdir(parents=True, exist_ok=True)


def strip_gps_exif(payload: bytes) -> bytes:
    """Remove GPS EXIF tags from image bytes; leave orientation and the rest alone.

    This runs at the earliest point on every upload path (O-9): before
    analysis, before any copy is written, before any log records a path. Only
    the GPS IFD (0x8825 and everything nested under it -- lat/long/altitude/
    timestamp/etc., the same 31 ``TAG_GPS_*`` tags the app-side
    ``ExifSanitizer`` enumerates) is dropped; Orientation and camera tags
    (Make, DateTime, ...) survive so a portrait phone photo does not come out
    sideways and the analysis pipeline keeps whatever it already relied on.

    Per O-8, EXIF stripping is quality assistance, not a blocking condition:
    any failure here (corrupt bytes, a format Pillow cannot round-trip, a
    malformed EXIF block) is logged and the original payload is returned
    unchanged so the upload still proceeds. The route's own decode/verify
    step remains the only gate that rejects a genuinely broken image.
    """
    try:
        image = Image.open(io.BytesIO(payload))
        exif = image.getexif()
        if ExifTags.Base.GPSInfo not in exif:
            return payload
        del exif[ExifTags.Base.GPSInfo]
        save_kwargs: dict[str, Any] = {"exif": exif}
        if image.format == "JPEG":
            # Reuse the source quantization tables so removing one EXIF tag
            # does not also silently recompress the photo at Pillow's default
            # JPEG quality (75).
            save_kwargs["quality"] = "keep"
        output = io.BytesIO()
        image.save(output, format=image.format, **save_kwargs)
        return output.getvalue()
    except Exception:
        logger.warning("gps_exif_strip_failed; continuing with the original upload", exc_info=True)
        return payload


def save_exif_stripped_input(job_id: str, payload: bytes) -> tuple[str, int]:
    """Decode and re-encode the upload so no EXIF -- location included -- is retained."""
    INPUT_DIR.mkdir(parents=True, exist_ok=True)
    image = Image.open(io.BytesIO(payload))
    image.load()
    # Bake the EXIF Orientation into the pixels before the metadata is
    # dropped below. Without this, a portrait photo whose sensor data is
    # landscape-plus-orientation-tag would be saved sideways with no tag left
    # to correct it.
    try:
        image = ImageOps.exif_transpose(image) or image
    except Exception:
        # Orientation correction is best-effort, same as the GPS strip above
        # (O-8): a malformed Orientation tag must not block the edit job.
        logger.warning("exif_orientation_transpose_failed; saving pixels as decoded", exc_info=True)
    output = io.BytesIO()
    if image.mode not in ("RGB", "RGBA"):
        image = image.convert("RGBA" if "A" in image.getbands() else "RGB")
    # Re-encoding a fresh PNG with no exif= keeps the source image's entire
    # EXIF block -- GPS included -- from ever reaching disk.
    image.save(output, format="PNG")
    path = INPUT_DIR / f"{job_id}.png"
    path.write_bytes(output.getvalue())
    try:
        storage_path = str(path.relative_to(SERVER_ROOT))
    except ValueError:
        # Tests may use an isolated temporary directory outside the server root.
        storage_path = str(path)
    return storage_path, path.stat().st_size


def save_shoot_photo(photo_id: str, payload: bytes) -> tuple[str, int]:
    """Persist a claimed-session photo as EXIF-free PNG until its session is claimed or expires."""
    SHOOT_DIR.mkdir(parents=True, exist_ok=True)
    image = Image.open(io.BytesIO(payload))
    image.load()
    image = ImageOps.exif_transpose(image) or image
    if image.mode not in ("RGB", "RGBA"):
        image = image.convert("RGBA" if "A" in image.getbands() else "RGB")
    path = SHOOT_DIR / f"{photo_id}.png"
    image.save(path, format="PNG")
    try:
        storage_path = str(path.relative_to(SERVER_ROOT))
    except ValueError:
        storage_path = str(path)
    return storage_path, path.stat().st_size
