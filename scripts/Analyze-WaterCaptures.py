"""Describe real screenshot samples; pixel statistics are not universal visual acceptance."""
import argparse
import csv
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image

ROIS = {"roof": (32, 80, 822, 244), "original_band": (520, 120, 854, 245)}


def read_image(path):
    with Image.open(path) as image:
        if image.size != (854, 480):
            raise ValueError(f"Unexpected screenshot dimensions: {path}: {image.size}")
        return np.asarray(image.convert("RGB"))


def statistics(rgb):
    result = {}
    for name, (x0, y0, x1, y1) in ROIS.items():
        region = rgb[y0:y1, x0:x1]
        result[name] = {"pixels": int(region.shape[0] * region.shape[1]),
                        "near_black_rgb_lt20": int(np.count_nonzero(np.all(region < 20, axis=2))),
                        "mean_rgb": np.mean(region, axis=(0, 1)).tolist()}
    return result


def analyze_matrix(directory):
    names = ["baseline", "no-lod", "flat", "eight-bounces", "raw-eight", "raw-two"]
    return {name: statistics(read_image(directory / f"gysf-water-{name}.png")) for name in names}


def analyze_sequence(directory, reference):
    with (directory / "frames.csv").open(newline="", encoding="utf-8") as file:
        rows = sorted(csv.DictReader(file), key=lambda row: int(row["rt_frame"]))
    if len(rows) < 120:
        raise ValueError("Incomplete sequence")
    samples, hashes, frames = [], set(), set()
    previous = None
    last_ns = None
    gaps = []
    for row in rows:
        frame = int(row["rt_frame"])
        if frame in frames:
            raise ValueError("Duplicate render-frame metadata")
        frames.add(frame)
        if row["near_ready"] != "true" or row["far_ready"] != str(reference).lower():
            raise ValueError("Unexpected near/far ownership")
        camera = [float(row[f"camera_{axis}"]) for axis in "xyz"]
        if camera != [64.5, 110.0, 32.5] or float(row["wave_speed"]) <= 0:
            raise ValueError("Camera moved or waves were frozen")
        if not re.fullmatch(r"frame-\d{4}\.png", row["file"]):
            raise ValueError("Invalid capture name")
        path = directory / row["file"]
        hashes.add(hashlib.sha256(path.read_bytes()).hexdigest())
        rgb = read_image(path)
        item = {"tick": int(row["tick"]), "rt_frame": frame, "file": row["file"],
                "request_ns": int(row["request_ns"]), "wave_strength": float(row["wave_strength"]),
                "wave_speed": float(row["wave_speed"]), "regions": statistics(rgb)}
        if previous is not None:
            delta = np.abs(rgb[80:244, 32:822].astype(np.int16) - previous.astype(np.int16))
            item["roof_adjacent_sample_mean_abs_rgb_change"] = float(delta.mean())
            item["roof_adjacent_sample_max_rgb_change"] = int(delta.max())
            gaps.append((item["request_ns"] - last_ns) / 1e9)
        previous = rgb[80:244, 32:822]
        last_ns = item["request_ns"]
        samples.append(item)
    if any(gap <= 0 for gap in gaps):
        raise ValueError("Non-monotonic request times")
    maximum_black = {name: max(item["regions"][name]["near_black_rgb_lt20"] for item in samples) for name in ROIS}
    return {"frames": len(rows), "distinct_png_hashes": len(hashes), "near_reference": reference,
            "request_span_seconds": (samples[-1]["request_ns"] - samples[0]["request_ns"]) / 1e9,
            "request_gap_seconds_median_p95_max": np.percentile(gaps, [50, 95, 100]).tolist(),
            "maximum_near_black_pixels": maximum_black, "samples": samples}


def export_video(directory, samples, output):
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("ffmpeg is required for --video")
    playlist = ["ffconcat version 1.0"]
    for index, item in enumerate(samples):
        duration = ((samples[index+1]["request_ns"] - item["request_ns"]) / 1e9
                    if index+1 < len(samples) else 0.05)
        playlist += [f"file '{item['file']}'", f"duration {duration:.9f}"]
    playlist.append(f"file '{samples[-1]['file']}'")
    source = directory / "preview.ffconcat"
    source.write_text("\n".join(playlist) + "\n", encoding="utf-8")
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-safe", "0", "-f", "concat",
                    "-i", str(source), "-fps_mode", "vfr", "-c:v", "libx264", "-crf", "20", "-preset", "medium",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(output)], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--sequence", action="store_true")
    parser.add_argument("--near-reference", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--video", type=Path)
    args = parser.parse_args()
    result = {"regions_xyxy": ROIS, "limits": [
        "Threshold counts and adjacent-image changes describe these captured samples only.",
        "Dark reflections and moving glints can be physically valid; counts do not classify their cause.",
        "Request metadata is not exact GPU readback timestamping or a frame-time benchmark.",
        "Different runs do not share an animation phase or stochastic history; do not subtract them as pixel-identical references."]}
    result["sequence" if args.sequence else "matrix"] = (analyze_sequence(args.directory, args.near_reference)
        if args.sequence else analyze_matrix(args.directory))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if args.video:
        if not args.sequence:
            raise ValueError("--video requires --sequence")
        export_video(args.directory, result["sequence"]["samples"], args.video)
    if args.sequence:
        print(json.dumps({key: value for key, value in result["sequence"].items() if key != "samples"}))
    else:
        print(json.dumps({name: {roi: data["near_black_rgb_lt20"] for roi, data in stats.items()}
                          for name, stats in result["matrix"].items()}))


if __name__ == "__main__":
    main()
