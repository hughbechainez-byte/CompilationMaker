#!/usr/bin/env python3
"""Deterministic host-only A/B checks for a released CompilationMaker output."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


def run(command: list[str], *, capture_binary: bool = False) -> bytes | str:
    completed = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=not capture_binary,
    )
    if completed.returncode != 0:
        stderr = completed.stderr.decode(errors="replace") if capture_binary else completed.stderr
        raise RuntimeError(f"command failed ({completed.returncode}): {' '.join(command)}\n{stderr[-4000:]}")
    return completed.stdout


def probe(ffprobe: str, path: Path) -> dict:
    return json.loads(
        run([ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)])
    )


def duration_ms(probe_data: dict) -> int:
    durations = []
    raw_format = probe_data.get("format", {}).get("duration")
    if raw_format not in (None, "N/A"):
        durations.append(float(raw_format))
    for stream in probe_data.get("streams", []):
        raw = stream.get("duration")
        if raw not in (None, "N/A"):
            durations.append(float(raw))
    return round(max(durations, default=0.0) * 1000.0)


def stream_summary(probe_data: dict) -> dict:
    streams = probe_data.get("streams", [])
    video = [stream for stream in streams if stream.get("codec_type") == "video"]
    audio = [stream for stream in streams if stream.get("codec_type") == "audio"]
    return {
        "durationMs": duration_ms(probe_data),
        "sizeBytes": int(probe_data.get("format", {}).get("size", 0) or 0),
        "videoTracks": len(video),
        "audioTracks": len(audio),
        "videoCodecs": [stream.get("codec_name") for stream in video],
        "audioCodecs": [stream.get("codec_name") for stream in audio],
        "width": int(video[0].get("width", 0)) if video else 0,
        "height": int(video[0].get("height", 0)) if video else 0,
    }


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def compare_ssim(
    ffmpeg: str,
    output: Path,
    reference: Path,
    reference_summary: dict,
    boundaries_seconds: list[float],
) -> dict:
    width = reference_summary["width"]
    height = reference_summary["height"]
    if width <= 0 or height <= 0:
        return {"passed": False, "error": "reference has no usable video dimensions"}
    with tempfile.TemporaryDirectory(prefix="compilationmaker-ssim-") as temporary_dir:
        stats_path = Path(temporary_dir) / "ssim.log"
        graph = (
            f"[0:v]fps=2,scale={width}:{height}:flags=bicubic,setsar=1,setpts=PTS-STARTPTS,format=yuv420p[out];"
            f"[1:v]fps=2,scale={width}:{height}:flags=bicubic,setsar=1,setpts=PTS-STARTPTS,format=yuv420p[ref];"
            f"[out][ref]ssim=stats_file='{stats_path.as_posix()}'"
        )
        run([
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(output),
            "-i",
            str(reference),
            "-filter_complex",
            graph,
            "-shortest",
            "-an",
            "-f",
            "null",
            os.devnull,
        ])
        values: list[tuple[float, float]] = []
        pattern = re.compile(r"n:(\d+).*?All:([0-9.]+)")
        for line in stats_path.read_text(encoding="utf-8", errors="replace").splitlines():
            match = pattern.search(line)
            if match:
                frame_index = int(match.group(1))
                values.append(((frame_index - 1) / 2.0, float(match.group(2))))

    scores = [score for _, score in values]
    outside_failures = [
        {"timeSeconds": time_seconds, "ssim": score}
        for time_seconds, score in values
        if score < 0.90
        and not any(abs(time_seconds - boundary) <= 1.0 for boundary in boundaries_seconds)
    ]
    mean = sum(scores) / len(scores) if scores else None
    p05 = percentile(scores, 0.05)
    passed = bool(scores) and mean >= 0.95 and p05 is not None and p05 >= 0.90 and not outside_failures
    return {
        "sampleCount": len(scores),
        "mean": mean,
        "p05": p05,
        "outsideBoundaryFailureCount": len(outside_failures),
        "outsideBoundaryFailures": outside_failures[:100],
        "passed": passed,
    }


def decode_pcm(ffmpeg: str, path: Path) -> bytes:
    return run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(path),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-f",
            "s16le",
            "-",
        ],
        capture_binary=True,
    )


def fft_clip_correlation(left, right, max_lag_samples: int) -> tuple[float | None, int | None]:
    import numpy as np

    length = min(left.size, right.size)
    if length <= max_lag_samples * 2:
        return None, None
    left = left[:length].astype(np.float64)
    right = right[:length].astype(np.float64)
    left -= left.mean()
    right -= right.mean()
    if left.std() < 1e-9 or right.std() < 1e-9:
        return None, None
    fft_size = 1 << (2 * length - 1).bit_length()
    convolution = np.fft.irfft(
        np.fft.rfft(left, fft_size) * np.fft.rfft(right[::-1], fft_size),
        fft_size,
    )
    left_sq = np.concatenate(([0.0], np.cumsum(left * left)))
    right_sq = np.concatenate(([0.0], np.cumsum(right * right)))
    best_score = -1.0
    best_lag = 0
    for lag in range(-max_lag_samples, max_lag_samples + 1):
        if lag >= 0:
            left_start, right_start, overlap = lag, 0, length - lag
        else:
            left_start, right_start, overlap = 0, -lag, length + lag
        numerator = float(convolution[length - 1 + lag])
        left_energy = float(left_sq[left_start + overlap] - left_sq[left_start])
        right_energy = float(right_sq[right_start + overlap] - right_sq[right_start])
        denominator = math.sqrt(max(0.0, left_energy * right_energy))
        score = numerator / denominator if denominator > 0.0 else -1.0
        if score > best_score:
            best_score = score
            best_lag = lag
    return best_score, best_lag


def compare_audio(ffmpeg: str, output: Path, reference: Path, clip_seconds: int = 40) -> dict:
    try:
        import numpy as np
    except ImportError as failure:
        return {"passed": False, "error": f"numpy is required for audio correlation: {failure}"}
    try:
        output_pcm = np.frombuffer(decode_pcm(ffmpeg, output), dtype="<i2")
        reference_pcm = np.frombuffer(decode_pcm(ffmpeg, reference), dtype="<i2")
    except RuntimeError as failure:
        return {"passed": False, "error": str(failure)}

    sample_rate = 16_000
    samples_per_clip = clip_seconds * sample_rate
    clip_count = min(output_pcm.size, reference_pcm.size) // samples_per_clip
    clips = []
    for index in range(clip_count):
        start = index * samples_per_clip
        end = start + samples_per_clip
        score, lag = fft_clip_correlation(output_pcm[start:end], reference_pcm[start:end], 1_600)
        clips.append({
            "index": index,
            "correlation": score,
            "bestOffsetMs": None if lag is None else lag * 1000.0 / sample_rate,
            "silentOrDegenerate": score is None,
        })
    valid_scores = [clip["correlation"] for clip in clips if clip["correlation"] is not None]
    mean = sum(valid_scores) / len(valid_scores) if valid_scores else None
    passed = (
        clip_count > 0
        and len(valid_scores) == clip_count
        and all(score >= 0.90 for score in valid_scores)
        and mean is not None
        and mean >= 0.95
    )
    return {
        "sampleRateHz": sample_rate,
        "clipSeconds": clip_seconds,
        "clipCount": clip_count,
        "outputSamples": int(output_pcm.size),
        "referenceSamples": int(reference_pcm.size),
        "meanCorrelation": mean,
        "clips": clips,
        "passed": passed,
    }


def decode_check(ffmpeg: str, path: Path, duration: int) -> dict:
    probes = []
    for fraction in (0.05, 0.50, 0.95):
        timestamp = max(0.0, duration / 1000.0 * fraction)
        try:
            run([
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-ss",
                f"{timestamp:.3f}",
                "-i",
                str(path),
                "-frames:v",
                "1",
                "-f",
                "null",
                os.devnull,
            ])
            probes.append({"fraction": fraction, "timestampSeconds": timestamp, "passed": True})
        except RuntimeError as failure:
            probes.append({"fraction": fraction, "timestampSeconds": timestamp, "passed": False, "error": str(failure)})
    return {"probes": probes, "passed": all(probe["passed"] for probe in probes)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--json", required=True, type=Path)
    parser.add_argument("--expected-duration-ms", type=int, default=400_000)
    parser.add_argument("--duration-tolerance-ms", type=int, default=2_000)
    parser.add_argument(
        "--boundary-seconds",
        default="10,50,90,130,170,210,250,290,330,370",
    )
    args = parser.parse_args()
    ffmpeg = shutil.which("ffmpeg")
    ffprobe = shutil.which("ffprobe")
    if ffmpeg is None or ffprobe is None:
        raise SystemExit("ffmpeg and ffprobe must be available on PATH")
    for path in (args.output, args.reference):
        if not path.is_file():
            raise SystemExit(f"media file does not exist: {path}")

    output_probe = probe(ffprobe, args.output)
    reference_probe = probe(ffprobe, args.reference)
    output_summary = stream_summary(output_probe)
    reference_summary = stream_summary(reference_probe)
    boundaries = [float(value) for value in args.boundary_seconds.split(",") if value.strip()]
    container_passed = (
        abs(output_summary["durationMs"] - args.expected_duration_ms) <= args.duration_tolerance_ms
        and output_summary["videoTracks"] > 0
        and output_summary["audioTracks"] > 0
        and output_summary["sizeBytes"] > 0
    )
    result = {
        "schemaVersion": 1,
        "output": output_summary,
        "reference": reference_summary,
        "container": {
            "expectedDurationMs": args.expected_duration_ms,
            "durationToleranceMs": args.duration_tolerance_ms,
            "durationDeltaMs": output_summary["durationMs"] - args.expected_duration_ms,
            "passed": container_passed,
        },
        "decode": decode_check(ffmpeg, args.output, output_summary["durationMs"]),
        "visual": compare_ssim(ffmpeg, args.output, args.reference, reference_summary, boundaries),
        "audio": compare_audio(ffmpeg, args.output, args.reference),
    }
    result["passed"] = all(
        result[key]["passed"] for key in ("container", "decode", "visual", "audio")
    )
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
    print(
        f"comparison={'PASS' if result['passed'] else 'FAIL'} "
        f"durationMs={output_summary['durationMs']} visual={result['visual'].get('passed')} "
        f"audio={result['audio'].get('passed')}"
    )
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
