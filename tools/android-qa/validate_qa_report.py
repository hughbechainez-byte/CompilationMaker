#!/usr/bin/env python3
"""Validate a production scan report against host-only fixture labels."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8-sig") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def sha256(path: Path | None) -> str | None:
    if path is None or not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def candidate_covers(candidates: list[dict], boundary_ms: int) -> bool:
    return any(
        int(candidate.get("startMs", -1)) <= boundary_ms <= int(candidate.get("endMs", -1))
        for candidate in candidates
    )


def evaluate(report: dict, labels: dict) -> tuple[list[dict], list[dict], list[dict]]:
    expected = labels.get("transitions", [])
    detected = report.get("transitionMarks", [])
    unused = set(range(len(detected)))
    matched: list[dict] = []
    missed: list[dict] = []
    candidates = report.get("candidates", [])

    for item in expected:
        boundary = int(item["eventBoundaryMs"])
        tolerance = int(item.get("toleranceMs", 500))
        compatible: list[tuple[int, int]] = []
        for index in unused:
            mark = detected[index]
            if mark.get("fromNumber") != item.get("from") or mark.get("toNumber") != item.get("to"):
                continue
            error = int(mark.get("eventBoundaryMs", -1)) - boundary
            if abs(error) <= tolerance:
                compatible.append((abs(error), index))
        if compatible:
            _, index = min(compatible)
            unused.remove(index)
            mark = detected[index]
            matched.append({
                "expected": item,
                "detected": mark,
                "eventBoundaryErrorMs": int(mark["eventBoundaryMs"]) - boundary,
            })
        else:
            missed.append({
                "expected": item,
                "candidateCoverage": candidate_covers(candidates, boundary),
                "earliestFailureStage": (
                    "OCR_OR_SEQUENCE_REJECTION"
                    if candidate_covers(candidates, boundary)
                    else "CANDIDATE_NOT_OPENED"
                ),
            })

    false_positives = [detected[index] for index in sorted(unused)]
    return matched, missed, false_positives


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--branch", default="master")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--api-level", type=int, required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--output-duration-ms", type=int)
    parser.add_argument("--output-size-bytes", type=int)
    args = parser.parse_args()

    report = load_json(args.report)
    labels = load_json(args.labels)
    matched, missed, false_positives = evaluate(report, labels)
    expected_count = len(labels.get("transitions", []))
    detected_count = len(report.get("transitionMarks", []))
    true_positives = len(matched)
    precision = true_positives / detected_count if detected_count else (1.0 if expected_count == 0 else 0.0)
    recall = true_positives / expected_count if expected_count else 1.0
    passed = not missed and not false_positives and detected_count == expected_count

    evidence = {
        "schemaVersion": 1,
        "applicationVersion": args.version,
        "commitSha": args.commit,
        "branch": args.branch,
        "fixtureId": labels.get("fixtureId"),
        "runId": args.run_id,
        "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
        "deviceModel": args.device,
        "androidApiLevel": args.api_level,
        "scannerVersion": report.get("scannerVersion"),
        "scanProfile": report.get("profileLabel"),
        "frameProvider": report.get("frameProvider"),
        "frameProviderFallbackReason": report.get("frameProviderFallbackReason"),
        "sourceVideoDurationMs": report.get("videoDurationMs"),
        "expectedTransitions": labels.get("transitions", []),
        "detectedTransitions": report.get("transitionMarks", []),
        "matches": matched,
        "falseNegatives": missed,
        "falsePositives": false_positives,
        "truePositives": true_positives,
        "precision": precision,
        "recall": recall,
        "candidateCount": report.get("candidateWindows"),
        "acceptedTransitionCount": report.get("acceptedTransitions"),
        "rejectedCandidateCount": report.get("rejectedCandidates"),
        "unresolvedIntervalCount": len(missed),
        "visualSampleCount": report.get("metrics", {}).get("visualSamples"),
        "ocrCallCount": report.get("metrics", {}).get("ocrCalls"),
        "providerFallbackCount": 1 if report.get("frameProviderFallbackReason") else 0,
        "scanWallClockMs": report.get("wallClockScanMs"),
        "videoDurationToWallTimeMultiple": report.get("scanSpeedMultiple"),
        "outputDurationMs": args.output_duration_ms,
        "outputSizeBytes": args.output_size_bytes,
        "outputVerificationResult": passed,
        "apkSha256": sha256(args.apk),
        "testApkSha256": sha256(args.test_apk),
        "fallbackUsed": report.get("fallbackUsed"),
        "result": "PASS" if passed else "FAIL",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(evidence, handle, indent=2)
        handle.write("\n")
    print(
        f"{'PASS' if passed else 'FAIL'} fixture={evidence['fixtureId']} "
        f"tp={true_positives} fp={len(false_positives)} fn={len(missed)} "
        f"precision={precision:.3f} recall={recall:.3f}"
    )
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
