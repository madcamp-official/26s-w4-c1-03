from __future__ import annotations

import os
import subprocess
import tempfile
import uuid
from pathlib import Path

from PIL import Image

from .service import ModelService, create_app, output_dir


def camera_trajectory_args(motion: str) -> list[str]:
    values = {
        "left": {"d_x": "-45"},
        "right": {"d_x": "45"},
        "up": {"d_y": "35"},
        "down": {"d_y": "-35"},
        "dolly_out": {"d_r": "0.12"},
    }.get(motion)
    if values is None:
        raise ValueError("unsupported viewpoint motion")
    # ViewCrafter indexes every camera argument even when only one axis moves.
    # Its argparse defaults are scalar values, so all axes must be supplied as
    # one-element lists to keep single_view_target from failing at runtime.
    defaults = {"d_theta": "0", "d_phi": "0", "d_r": "0", "d_x": "0", "d_y": "0"}
    defaults.update(values)
    return [item for key, value in defaults.items() for item in (f"--{key}", value)]


class ViewCrafterBackend:
    def __init__(self) -> None:
        self.repository = Path(os.getenv("GAMDO_VIEWCRAFTER_REPOSITORY", "/opt/gamdo/model-services/ViewCrafter"))
        self.python = os.getenv("GAMDO_VIEWCRAFTER_PYTHON", "/opt/gamdo/model-services/viewcrafter-venv/bin/python")
        self.checkpoint = Path(os.getenv("GAMDO_VIEWCRAFTER_CHECKPOINT", str(self.repository / "checkpoints/model.ckpt")))
        self.dust3r = Path(os.getenv("GAMDO_DUST3R_CHECKPOINT", str(self.repository / "checkpoints/DUSt3R_ViTLarge_BaseDecoder_512_dpt.pth")))
        self.config = Path(os.getenv(
            "GAMDO_VIEWCRAFTER_CONFIG",
            str(self.repository / "configs/inference_pvd_512.yaml"),
        ))
        self.height = int(os.getenv("GAMDO_VIEWCRAFTER_HEIGHT", "320"))
        self.width = int(os.getenv("GAMDO_VIEWCRAFTER_WIDTH", "512"))
        self.video_length = int(os.getenv("GAMDO_VIEWCRAFTER_VIDEO_LENGTH", "16"))
        self._runtime_check: tuple[bool, str] | None = None

    def ready(self) -> tuple[bool, str]:
        missing = [str(path) for path in (Path(self.python), self.checkpoint, self.dust3r, self.config) if not path.is_file()]
        if missing:
            return False, "missing: " + ", ".join(missing)
        minimum_checkpoint_bytes = int(os.getenv("GAMDO_VIEWCRAFTER_MIN_CHECKPOINT_BYTES", "9000000000"))
        if self.checkpoint.stat().st_size < minimum_checkpoint_bytes:
            return False, "ViewCrafter checkpoint is incomplete"
        minimum_dust3r_bytes = int(os.getenv("GAMDO_DUST3R_MIN_CHECKPOINT_BYTES", "1000000000"))
        if self.dust3r.stat().st_size < minimum_dust3r_bytes:
            return False, "DUSt3R checkpoint is incomplete"
        if self._runtime_check is None:
            try:
                subprocess.run(
                    [self.python, "-c", "import pytorch3d; import imageio; import pytorch_lightning"],
                    cwd=self.repository,
                    check=True,
                    timeout=30,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                self._runtime_check = True, "model files and runtime present"
            except (OSError, subprocess.SubprocessError):
                self._runtime_check = False, "ViewCrafter runtime dependencies are unavailable"
        return self._runtime_check

    def generate(self, image: Path, operation: dict, output: Path, seed: int) -> None:
        import imageio.v3 as iio

        motion = operation.get("motion", "dolly_out")
        trajectory = camera_trajectory_args(motion)
        with tempfile.TemporaryDirectory(prefix="gamdo-viewcrafter-") as directory:
            work = Path(directory)
            source = work / "input.png"
            with Image.open(image) as decoded:
                original_size = decoded.size
                decoded.convert("RGB").save(source)
            name = uuid.uuid4().hex
            command = [
                self.python, "inference.py", "--image_dir", str(source), "--out_dir", str(work),
                "--exp_name", name, "--mode", "single_view_target", "--ckpt_path", str(self.checkpoint),
                "--model_path", str(self.dust3r), "--config", str(self.config),
                "--height", str(self.height), "--width", str(self.width),
                "--video_length", str(self.video_length), "--ddim_steps", "25",
                "--seed", str(seed), *trajectory,
            ]
            timeout = float(os.getenv("GAMDO_VIEWCRAFTER_TIMEOUT_SECONDS", "600"))
            subprocess.run(command, cwd=self.repository, check=True, timeout=timeout)
            video = work / name / "diffusion0.mp4"
            frames = iio.imread(video, plugin="ffmpeg")
            frame = frames[min(len(frames) - 1, max(1, round(len(frames) * 0.7)))]
            Image.fromarray(frame).resize(original_size, Image.Resampling.LANCZOS).save(output, "PNG")


app = create_app(ModelService("viewpoint", ViewCrafterBackend(), output_dir()))
