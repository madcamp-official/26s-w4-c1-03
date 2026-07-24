from __future__ import annotations

import io
from pathlib import Path

from PIL import Image


SERVER_ROOT = Path(__file__).resolve().parents[1]
INPUT_DIR = SERVER_ROOT / "storage" / "inputs"
RESULT_DIR = SERVER_ROOT / "storage" / "results"
TMP_DIR = SERVER_ROOT / "storage" / "tmp"


def ensure_storage() -> None:
    for directory in (INPUT_DIR, RESULT_DIR, TMP_DIR):
        directory.mkdir(parents=True, exist_ok=True)


def save_exif_stripped_input(job_id: str, payload: bytes) -> tuple[str, int]:
    """Decode and re-encode the upload so location EXIF cannot be retained."""
    INPUT_DIR.mkdir(parents=True, exist_ok=True)
    image = Image.open(io.BytesIO(payload))
    image.load()
    output = io.BytesIO()
    if image.mode not in ("RGB", "RGBA"):
        image = image.convert("RGBA" if "A" in image.getbands() else "RGB")
    # Re-encoding a fresh PNG drops the source image's EXIF block.
    image.save(output, format="PNG")
    path = INPUT_DIR / f"{job_id}.png"
    path.write_bytes(output.getvalue())
    try:
        storage_path = str(path.relative_to(SERVER_ROOT))
    except ValueError:
        # Tests may use an isolated temporary directory outside the server root.
        storage_path = str(path)
    return storage_path, path.stat().st_size
