"""Measure InsightFace similarity scores without changing the production threshold.

Manifest format::

    {"pairs": [
      {"id": "same_01", "original": "...", "candidate": "...", "sameIdentity": true}
    ]}

The script keeps embeddings in memory only. It reports scores and threshold
metrics to stdout; it never writes a new threshold or stores face vectors.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image

from app.generative import InsightFaceVerifier


def load_manifest(path: Path) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    pairs = payload.get("pairs") if isinstance(payload, dict) else payload
    if not isinstance(pairs, list) or not pairs:
        raise ValueError("manifest must contain a non-empty 'pairs' list")
    return pairs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--thresholds", default="0.25,0.30,0.35,0.40,0.45,0.50")
    args = parser.parse_args()

    pairs = load_manifest(args.manifest)
    if len(pairs) < 5:
        raise SystemExit("at least 5 original/candidate pairs are required")
    thresholds = [float(value) for value in args.thresholds.split(",")]
    verifier = InsightFaceVerifier(threshold=thresholds[0])
    observations: list[dict] = []
    for index, pair in enumerate(pairs):
        original_path = Path(pair["original"])
        candidate_path = Path(pair["candidate"])
        with Image.open(original_path) as original, Image.open(candidate_path) as candidate:
            score = verifier.similarity(original, candidate)
        observations.append(
            {
                "id": str(pair.get("id", index)),
                "score": score,
                "sameIdentity": pair.get("sameIdentity"),
            }
        )

    valid_scores = [item for item in observations if item["score"] is not None]
    if len(valid_scores) < 5:
        raise SystemExit(
            f"calibration requires at least 5 pairs with detectable faces; "
            f"only {len(valid_scores)} were measurable"
        )
    if any(not isinstance(item["sameIdentity"], bool) for item in valid_scores):
        raise SystemExit("every measurable pair must include a boolean sameIdentity label")

    print(json.dumps({"pairs": observations, "thresholds": thresholds}, ensure_ascii=False, indent=2))
    print("threshold metrics:")
    for threshold in thresholds:
        correct = sum((item["score"] >= threshold) == item["sameIdentity"] for item in valid_scores)
        print(f"  {threshold:.2f}: {correct}/{len(valid_scores)} correct")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
