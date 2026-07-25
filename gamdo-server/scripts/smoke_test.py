"""End-to-end smoke test against a running server — prints the real values.

Unlike `tests/` (TestClient, in-process) this drives the HTTP surface a device
would hit, so it also proves the URL prefix, the X-Device-Id gate and the
`/edit-jobs` state machine over the wire.

    python scripts/smoke_test.py                     # localhost, synthetic image
    python scripts/smoke_test.py --image photo.jpg    # real photo
    python scripts/smoke_test.py --base-url http://10.0.0.5:8000
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import time
from pathlib import Path

import httpx
from PIL import Image, ImageDraw

PREFIX = "/api/v1"
DEVICE_ID = "smoke-test-device"
failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}{f' — {detail}' if detail else ''}")
    if not ok:
        failures.append(label)


def synthetic_photo() -> bytes:
    """A skin-tone blob on a plain background — enough for the palette/subject path."""
    image = Image.new("RGB", (900, 1200), (206, 214, 220))
    draw = ImageDraw.Draw(image)
    draw.ellipse((330, 180, 570, 480), fill=(214, 168, 138))
    draw.rectangle((300, 470, 600, 1050), fill=(96, 108, 126))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=88)
    return buffer.getvalue()


def section(title: str) -> None:
    print(f"\n{title}")


def run(base_url: str, image_bytes: bytes, image_name: str) -> None:
    headers = {"X-Device-Id": DEVICE_ID}
    with httpx.Client(base_url=base_url, timeout=30.0) as client:
        section(f"1. health / device-id gate  ({base_url})")
        response = client.get("/health")
        check("GET /health 200", response.status_code == 200, response.text.strip())
        response = client.get(f"{PREFIX}/presets")
        check(
            "GET /presets without X-Device-Id 400",
            response.status_code == 400 and response.json().get("code") == "missing_device_id",
            f"{response.status_code} {response.text.strip()[:80]}",
        )

        section("2. GET /presets — 6 presets with real composition/color values")
        response = client.get(f"{PREFIX}/presets", headers=headers)
        presets = response.json() if response.status_code == 200 else []
        check("200 + 6 presets", response.status_code == 200 and len(presets) == 6, f"{len(presets)} items")
        etag = response.headers.get("etag", "")
        check("ETag present", bool(etag), etag[:24] + "…" if etag else "missing")
        for preset in presets:
            composition, color = preset["composition"], preset["color"]
            print(
                f"      {preset['id']:<16} pos={composition['subjectPosition']:<12}"
                f" scale={composition['subjectScaleRange']} headroom={composition['headroomRange']}"
                f" temp={color['colorTemperature']}K ev={color['exposureBias']:+.2f}"
            )
        if etag:
            response = client.get(f"{PREFIX}/presets", headers={**headers, "If-None-Match": etag})
            check("ETag revalidation 304", response.status_code == 304, str(response.status_code))

        section("3. POST /references/analyze — analysis + targetComposition + colorTarget")
        started = time.monotonic()
        response = client.post(
            f"{PREFIX}/references/analyze",
            headers=headers,
            files={"image": (image_name, image_bytes, "image/jpeg")},
        )
        elapsed = time.monotonic() - started
        check("200", response.status_code == 200, f"{response.status_code} in {elapsed:.2f}s")
        check("under the 5s budget", elapsed < 5.0, f"{elapsed:.2f}s")
        if response.status_code == 200:
            body = response.json()
            analysis = body["analysis"]
            print(f"      peopleCount={analysis['peopleCount']} subjects={analysis['subjects']}")
            print(
                f"      palette={analysis['palette']} temp={analysis['colorTemperature']}K"
                f" aspect={analysis['aspectRatio']} background={analysis['backgroundRatio']}"
            )
            print(f"      targetComposition={json.dumps(body['targetComposition'], ensure_ascii=False)}")
            print(f"      colorTarget.exposureBias={body['colorTarget']['exposureBias']}")
            for field in ("analysis", "targetComposition", "colorTarget"):
                check(f"response has {field}", field in body)
            # horizon/tilt/cameraHeight are still constants (MediaPipe not wired) —
            # surfaced so nobody mistakes them for measured values.
            print(
                f"      NOTE constants (not measured yet): horizon={analysis['horizon']}"
                f" tilt={analysis['tilt']} cameraHeight={analysis['cameraHeight']}"
            )

        section("4. /edit-jobs — queued → processing → fallback")
        job_id = f"job_smoke_{int(time.time())}"
        response = client.post(
            f"{PREFIX}/edit-jobs",
            headers=headers,
            data={
                "jobId": job_id,
                "captureRef": "cap_smoke_test",
                "operations": json.dumps([{"type": "remove_objects", "maskAreaRatio": 0.12}]),
                "resultCount": 2,
            },
            files={"image": (image_name, image_bytes, "image/jpeg")},
        )
        check("POST 202 queued", response.status_code == 202 and response.json().get("status") == "queued",
              f"{response.status_code} {response.text.strip()[:80]}")
        statuses = []
        for _ in range(3):
            poll = client.get(f"{PREFIX}/edit-jobs/{job_id}", headers=headers)
            if poll.status_code != 200:
                break
            body = poll.json()
            statuses.append(body["status"])
            print(f"      status={body['status']:<11} stage={body['progressStage']}"
                  f" results={len(body['results'])} failReason={body['failReason']}")
        check("reaches a terminal state", statuses and statuses[-1] in {"fallback", "done", "failed"},
              " → ".join(statuses))
        check("no result image is invented on fallback",
              statuses[-1] != "fallback" or not client.get(f"{PREFIX}/edit-jobs/{job_id}",
                                                           headers=headers).json()["results"])

        section("5. error contracts")
        response = client.get(f"{PREFIX}/edit-jobs/job_does_not_exist", headers=headers)
        check("unknown jobId 404", response.status_code == 404, response.text.strip()[:70])
        response = client.post(f"{PREFIX}/edit-jobs", headers=headers, data={"jobId": job_id})
        check("missing fields 422", response.status_code == 422, str(response.status_code))
        response = client.post(
            f"{PREFIX}/edit-jobs",
            headers=headers,
            data={
                "jobId": f"{job_id}_dup",
                "captureRef": "cap_smoke_test",
                "operations": json.dumps([{"type": "remove_objects", "maskAreaRatio": 0.9}]),
            },
            files={"image": (image_name, image_bytes, "image/jpeg")},
        )
        check("mask over the 30% area limit 422",
              response.status_code == 422 and response.json().get("code") == "edit_area_limit_exceeded",
              f"{response.status_code} {response.json().get('code')}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--image", type=Path, help="real photo to analyze (default: synthetic)")
    args = parser.parse_args()

    if args.image:
        image_bytes, image_name = args.image.read_bytes(), args.image.name
    else:
        image_bytes, image_name = synthetic_photo(), "synthetic.jpg"
    print(f"GAMDO server smoke test — image: {image_name} ({len(image_bytes) / 1024:.0f} KB)")

    try:
        run(args.base_url, image_bytes, image_name)
    except httpx.ConnectError:
        print(f"\ncannot reach {args.base_url} — start the server first:")
        print("  python -m uvicorn app.main:app --host 0.0.0.0 --port 8000")
        return 2

    print()
    if failures:
        print(f"smoke test FAILED ({len(failures)}): " + ", ".join(failures))
        return 1
    print("smoke test passed — every endpoint answered with real values")
    return 0


if __name__ == "__main__":
    sys.exit(main())
