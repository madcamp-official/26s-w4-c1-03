from __future__ import annotations

import argparse
import json
import os
import gc
import subprocess
import sys
from pathlib import Path

from PIL import Image

from .service import ModelService, create_app, output_dir


class IcLightBackend:
    def __init__(self) -> None:
        self.repository = Path(os.getenv("GAMDO_ICLIGHT_REPOSITORY", "/opt/gamdo/model-services/IC-Light"))
        self._runtime: dict | None = None

    def ready(self) -> tuple[bool, str]:
        source = self.repository / "gradio_demo.py"
        offset = self.repository / "models" / "iclight_sd15_fc.safetensors"
        if not source.is_file():
            return False, "IC-Light source is missing"
        if not offset.is_file():
            return False, "IC-Light model is missing"
        return True, "model files present"

    def _load(self) -> dict:
        if self._runtime is None:
            source = (self.repository / "gradio_demo.py").read_text(encoding="utf-8")
            # The official file starts its Gradio UI unconditionally. Execute only
            # the official model/runtime definitions preceding the UI declaration.
            model_source, marker, _ = source.partition("block = gr.Blocks()")
            if not marker:
                raise RuntimeError("unsupported IC-Light source revision")
            namespace = {"__file__": str(self.repository / "gradio_demo.py"), "__name__": "gamdo_iclight_runtime"}
            previous = Path.cwd()
            try:
                os.chdir(self.repository)
                exec(compile(model_source, str(self.repository / "gradio_demo.py"), "exec"), namespace)
            finally:
                os.chdir(previous)
            self._runtime = namespace
        return self._runtime

    def generate(self, image: Path, operation: dict, output: Path, seed: int) -> None:
        if os.getenv("GAMDO_ICLIGHT_CHILD") != "1":
            environment = os.environ.copy()
            environment["GAMDO_ICLIGHT_CHILD"] = "1"
            subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "model_services.iclight_service",
                    "--input",
                    str(image),
                    "--output",
                    str(output),
                    "--operation",
                    json.dumps(operation, separators=(",", ":")),
                    "--seed",
                    str(seed),
                ],
                cwd=Path(os.getenv("GAMDO_SERVER_ROOT", "/opt/gamdo/server")),
                env=environment,
                check=True,
                timeout=240,
            )
            return
        self._generate_local(image, operation, output, seed)

    def _generate_local(self, image: Path, operation: dict, output: Path, seed: int) -> None:
        runtime = self._load()
        with Image.open(image) as decoded:
            rgb = decoded.convert("RGB")
            original_size = rgb.size
            longest = max(original_size)
            scale = min(1.0, 768.0 / longest)
            width = max(256, round(original_size[0] * scale / 64) * 64)
            height = max(256, round(original_size[1] * scale / 64) * 64)
            source = runtime["np"].asarray(rgb)
        direction = operation.get("direction", "front")
        bg_source = {
            "left": runtime["BGSource"].LEFT.value,
            "right": runtime["BGSource"].RIGHT.value,
            "front": runtime["BGSource"].NONE.value,
        }.get(direction, runtime["BGSource"].NONE.value)
        strength = float(operation.get("strength", 0.65))
        results = runtime["process"](
            source,
            "natural balanced illumination, preserve subject and scene",
            width,
            height,
            1,
            seed,
            20,
            "best quality, natural photography",
            "changed identity, distorted geometry, cropped, oversaturated",
            2.0,
            1.0,
            0.15 + strength * 0.2,
            0.75,
            bg_source,
        )
        Image.fromarray(results[0]).resize(original_size, Image.Resampling.LANCZOS).save(output, "PNG")

    def release(self) -> None:
        """Drop the resident SD/IC-Light graph so the next GPU provider has room."""
        self._runtime = None
        gc.collect()
        try:
            import torch

            if torch.cuda.is_available():
                torch.cuda.empty_cache()
                torch.cuda.ipc_collect()
        except (ImportError, RuntimeError):
            pass


app = create_app(ModelService("relight", IcLightBackend(), output_dir()))


def _run_child() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--operation", required=True)
    parser.add_argument("--seed", type=int, required=True)
    arguments = parser.parse_args()
    IcLightBackend()._generate_local(
        arguments.input,
        json.loads(arguments.operation),
        arguments.output,
        arguments.seed,
    )


if __name__ == "__main__":
    _run_child()
