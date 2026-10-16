"""Validate fixed-camera smoke evidence and summarize full-frame cadence, not GPU pass time."""
import argparse
import csv
import json
import math
import statistics
import hashlib
from collections import defaultdict
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("csv", type=Path)
p.add_argument("--output", type=Path)
p.add_argument("--exclude-phases", default="", help="Explicit exclusions, recorded in output; never silently discard drift")
p.add_argument("--diagnostic", action="store_true", help="Report missing/changing geometry versions without accepting a controlled comparison")
p.add_argument("--gpu", type=Path, help="Optional sampled GPU timestamps joined by exact RT frame serial")
p.add_argument("--validation", action="store_true", help="Explicit validation-layer diagnostic; never qualify production cadence")
a = p.parse_args()
groups = defaultdict(list)
with a.csv.open(newline="", encoding="utf-8-sig") as f:
    reader = csv.DictReader(f)
    required = {"cameraX", "cameraY", "cameraZ", "yaw", "pitch", "screen", "intervalMs", "phase", "preset"}
    if not required.issubset(reader.fieldnames or []):
        raise SystemExit("INVALID: CSV lacks camera/screen invariants; do not accept it as a fixed-scene comparison")
    for row in reader:
        groups[int(row["phase"])].append(row)
if not groups:
    raise SystemExit("INVALID: no measured frames")
for key in ("cameraX", "cameraY", "cameraZ", "yaw", "pitch"):
    vals = [float(r[key]) for rows in groups.values() for r in rows]
    if not all(map(math.isfinite, vals)) or max(vals)-min(vals) > .001:
        raise SystemExit(f"INVALID: camera {key} differs between model phases")
report = {"kind": "full-frame wall cadence; geometry acceptance is separate from instrumentation", "phases": []}
metadata_file = a.csv.parent / "run.json"
metadata = json.loads(metadata_file.read_text(encoding="utf-8-sig")) if metadata_file.exists() else None
report["runMetadata"] = metadata
if metadata is not None:
    expected = set(range(len(metadata["presets"].split(","))))
    if set(groups) != expected:
        raise SystemExit("INVALID: measured phases do not match the requested run")
gpu = {}
if a.gpu:
    with a.gpu.open(newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            serial = int(row["rtFrame"])
            if serial in gpu:
                raise SystemExit("INVALID: duplicate GPU frame serial")
            gpu[serial] = row
report["gpuInstrumented"] = bool(a.gpu)
excluded = {int(v) for v in a.exclude_phases.split(",") if v}
report["excludedPhases"] = sorted(excluded)
for phase, rows in sorted(groups.items()):
    if len(rows) < 60:
        raise SystemExit(f"INVALID: phase {phase} has too few frames")
    for key in ("cameraX", "cameraY", "cameraZ", "yaw", "pitch"):
        vals = [float(r[key]) for r in rows]
        if not all(map(math.isfinite, vals)) or max(vals) - min(vals) > .001:
            raise SystemExit(f"INVALID: phase {phase} changed {key}")
    if any(r["screen"] != "none" for r in rows):
        raise SystemExit(f"INVALID: phase {phase} had an open screen")
    for key in ("rr", "preset", "nearRadius"):
        if len({r[key] for r in rows}) != 1:
            raise SystemExit(f"INVALID: phase {phase} changed {key}")
    if (rows[0]["rr"] == "true") != (int(rows[0]["preset"]) >= 0):
        raise SystemExit(f"INVALID: phase {phase} has inconsistent RR state")
    intervals = sorted(float(r["intervalMs"]) for r in rows)
    if not all(math.isfinite(v) and v > 0 for v in intervals):
        raise SystemExit("INVALID: non-positive or non-finite frame interval")
    item = {"phase": phase, "preset": int(rows[0]["preset"]), "nearRadius": int(rows[0]["nearRadius"]), "frames": len(rows),
            "meanMs": statistics.mean(intervals), "medianMs": statistics.median(intervals),
            "p95Ms": intervals[int(len(rows)*.95)], "p99Ms": intervals[int(len(rows)*.99)],
            "fps": 1000 / statistics.mean(intervals)}
    render_keys = ("quality", "renderWidth", "renderHeight", "displayWidth", "displayHeight", "filter", "spp", "bounces", "finiteWater", "omm", "nearPending", "lodPending")
    item["renderEvidence"] = all(k in rows[0] for k in render_keys)
    for column in ("waterShader", "waterHitShader"):
        if column in rows[0]:
            shaders = {r[column] for r in rows}
            if len(shaders) != 1:
                raise SystemExit("INVALID: shader changed inside measured phase")
            item[column] = next(iter(shaders))
    for column in ("waterBoundsVariant", "waterBoundsSource", "waterBoundsChildren", "waterWaveStrength", "waterWaveSpeed", "waterWaveCount"):
        if column in rows[0]:
            values = {r[column] for r in rows}
            if len(values) != 1:
                raise SystemExit("INVALID: water bounds or wave settings changed inside measured phase")
            item[column] = next(iter(values))
    if item["renderEvidence"]:
        for key in render_keys:
            if len({r[key] for r in rows}) != 1:
                raise SystemExit(f"INVALID: phase {phase} changed {key}")
            item[key] = rows[0][key]
        if item["filter"] != ("dlss-rr" if rows[0]["rr"] == "true" else "svgf"):
            raise SystemExit("INVALID: unexpected actual filter")
        if item["nearPending"] != "false" or item["lodPending"] != "false":
            raise SystemExit("INVALID: geometry work during measurement")
        for key in ("quality", "renderWidth", "renderHeight", "displayWidth", "displayHeight", "spp", "bounces"):
            item[key] = int(item[key])
        if min(item[k] for k in ("renderWidth", "renderHeight", "displayWidth", "displayHeight", "spp", "bounces")) <= 0:
            raise SystemExit("INVALID: non-positive rendering dimension or trace setting")
        if item["filter"] == "svgf" or item["quality"] == 5:
            if (item["renderWidth"], item["renderHeight"]) != (item["displayWidth"], item["displayHeight"]):
                raise SystemExit("INVALID: expected native rendering dimensions")
    if gpu:
        paired = []
        for row in rows:
            timing = gpu.get(int(row["rtFrame"]))
            if timing is None:
                continue
            for key in ("preset", "quality", "renderWidth", "renderHeight", "displayWidth", "displayHeight", "filter"):
                if key != "preset" or rows[0]["rr"] == "true":
                    if timing[key] != row[key]:
                        raise SystemExit(f"INVALID: GPU frame {row['rtFrame']} disagrees on {key}")
            paired.append(timing)
        if len(paired) < 8:
            raise SystemExit(f"INVALID: too few exact GPU timing pairs in phase {phase}")
        stages = ("prepareMs", "waterCameraMs", "primaryMs", "indirectMs", "reconstructMs", "exposureMs", "displayMs", "copyMs")
        for timing in paired:
            vals = [float(timing[k]) for k in (*stages, "totalGpuMs")]
            if not all(math.isfinite(v) and v >= 0 for v in vals) or abs(sum(vals[:-1])-vals[-1]) > .00002:
                raise SystemExit("INVALID: GPU duration or interval sum")
        item["gpuSamples"] = len(paired)
        item["gpuMeanMs"] = {k: statistics.mean(float(t[k]) for t in paired) for k in (*stages, "totalGpuMs")}
    for key in ("nearInstances", "lodSections", "processBytes", "budgetBytes"):
        vals = [int(r[key]) for r in rows]
        item[key] = [min(vals), max(vals)]
    item["geometryStable"] = all(item[k][0] == item[k][1] and item[k][0] > 0
                                 for k in ("nearInstances", "lodSections"))
    version_keys = ("lodRevision", "texturedBuilds", "atomicSwaps", "nearTable", "nearBuildToken")
    item["sceneVersionEvidence"] = all(k in rows[0] for k in version_keys)
    if item["sceneVersionEvidence"]:
        for key in version_keys:
            vals = [int(r[key]) for r in rows]
            item[key] = [min(vals), max(vals)]
        item["versionsStable"] = all(item[k][0] == item[k][1] for k in version_keys)
    if "waterUploads" in rows[0]:
        for key in ("waterUploads", "waterKnownNodes", "waterPending"):
            vals = [int(r[key]) for r in rows]
            if min(vals) != max(vals):
                raise SystemExit(f"INVALID: water field {key} changed during phase {phase}")
            item[key] = vals[0]
    item["excluded"] = phase in excluded
    report["phases"].append(item)
report["geometryStableAcrossAllPhases"] = all(
    len({tuple(item[k]) for item in report["phases"]}) == 1
    for k in ("nearInstances", "lodSections")) and all(item["geometryStable"] for item in report["phases"])
included = [item for item in report["phases"] if not item["excluded"]]
if metadata and metadata.get("waterShaderBaseline"):
    baseline_hash = metadata.get("waterShaderBaselineSha256")
    if not baseline_hash:
        baseline_file = Path(metadata["waterShaderBaseline"])
        baseline_hash = hashlib.sha256(baseline_file.read_bytes()).hexdigest()
    sequence = [int(v) for v in metadata["waterShaderSequence"].split(",")]
    if len(sequence) == 1:
        sequence *= len(report["phases"])
    if len(sequence) != len(report["phases"]) or any(v not in (0, 1) for v in sequence):
        raise SystemExit("INVALID: shader comparison sequence")
    candidates = set()
    for item, variant in zip(report["phases"], sequence):
        shader = item.get("waterShader", "")
        if len(shader) != 64 or any(c not in "0123456789abcdef" for c in shader):
            raise SystemExit("INVALID: missing actual shader identity")
        if (shader == baseline_hash) != (variant == 0):
            raise SystemExit("INVALID: actual shader does not match requested A/B order")
        item["shaderVariant"] = variant
        if variant:
            candidates.add(shader)
    if len(candidates) > 1:
        raise SystemExit("INVALID: candidate shader changed between phases")
    shader_settings = ("preset", "quality", "renderWidth", "renderHeight", "displayWidth", "displayHeight",
                       "filter", "spp", "bounces", "finiteWater", "omm")
    if any(any(k not in item for item in report["phases"]) or
           len({item[k] for item in report["phases"]}) != 1 for k in shader_settings):
        raise SystemExit("INVALID: shader A/B requires matching recorded render settings")
    report["shaderComparison"] = {"baseline": baseline_hash, "candidates": sorted(candidates), "sequence": sequence,
                                 "orderValidated": True, "settingsMatched": True}
    if metadata.get("waterHitShaderBaseline"):
        hit_baseline = metadata.get("waterHitShaderBaselineSha256")
        if not hit_baseline:
            hit_baseline = hashlib.sha256(Path(metadata["waterHitShaderBaseline"]).read_bytes()).hexdigest()
        hit_candidates = set()
        for item, variant in zip(report["phases"], sequence):
            shader = item.get("waterHitShader", "")
            if len(shader) != 64 or any(c not in "0123456789abcdef" for c in shader):
                raise SystemExit("INVALID: missing actual hit-stage shader identity")
            if variant == 0 and shader != hit_baseline:
                raise SystemExit("INVALID: actual hit-stage baseline shader does not match")
            if variant:
                hit_candidates.add(shader)
        if len(hit_candidates) > 1:
            raise SystemExit("INVALID: hit-stage candidate shader changed between phases")
        report["shaderComparison"]["hitStage"] = {"baseline": hit_baseline, "candidates": sorted(hit_candidates)}
if metadata and metadata.get("compareWaterBounds"):
    sequence = [int(v) for v in metadata["waterBoundsSequence"].split(",")]
    if len(sequence) == 1:
        sequence *= len(report["phases"])
    if len(sequence) != len(report["phases"]) or any(v not in (0, 1) for v in sequence):
        raise SystemExit("INVALID: water bounds comparison sequence")
    for item, variant in zip(report["phases"], sequence):
        if str(variant) != item.get("waterBoundsVariant"):
            raise SystemExit("INVALID: actual water bounds variant does not match requested order")
        source = item.get("waterBoundsSource", "")
        if len(source) != 64 or any(c not in "0123456789abcdef" for c in source):
            raise SystemExit("INVALID: missing immutable water bounds source identity")
        if float(item.get("waterWaveStrength", -1)) != 1 or float(item.get("waterWaveSpeed", -1)) != 0 or int(item.get("waterWaveCount", -1)) != 7:
            raise SystemExit("INVALID: bounds comparison requires frozen strength-1 seven-component waves")
    fixed = ("preset", "quality", "renderWidth", "renderHeight", "displayWidth", "displayHeight", "filter", "spp", "bounces",
             "finiteWater", "omm", "waterBoundsSource", "waterBoundsChildren", "waterShader", "waterHitShader")
    if any(any(k not in item for item in report["phases"]) or len({item[k] for item in report["phases"]}) != 1 for k in fixed):
        raise SystemExit("INVALID: bounds comparison changed physical inputs or recorded render settings")
    children = int(report["phases"][0]["waterBoundsChildren"])
    if children <= 0:
        raise SystemExit("INVALID: no matched water children")
    report["boundsComparison"] = {"sequence": sequence, "source": source, "children": children, "orderValidated": True, "settingsMatched": True}
report["selectedGeometryStable"] = bool(included) and all(item["geometryStable"] for item in included) and all(
    len({tuple(item[k]) for item in included}) == 1 for k in ("nearInstances", "lodSections")) and len({item["nearRadius"] for item in included})==1
report["selectedSceneVersionsStable"] = bool(included) and all(item.get("versionsStable", False) for item in included)
if report["selectedSceneVersionsStable"]:
    report["selectedSceneVersionsStable"] = all(len({tuple(item[k]) for item in included}) == 1 for k in version_keys)
report["comparisonAccepted"] = report["selectedGeometryStable"] and report["selectedSceneVersionsStable"]
if included and all(item["renderEvidence"] for item in included):
    fixed_render = ("displayWidth", "displayHeight", "spp", "bounces", "finiteWater", "omm")
    report["renderSettingsStable"] = all(len({item[k] for item in included}) == 1 for k in fixed_render)
    report["comparisonAccepted"] &= report["renderSettingsStable"]
if included and all("waterUploads" in item for item in included):
    report["waterFieldStable"] = all(len({item[k] for item in included}) == 1 for k in ("waterUploads", "waterKnownNodes", "waterPending"))
    report["comparisonAccepted"] &= report["waterFieldStable"]
report["validationDiagnostic"] = a.validation or bool(metadata and metadata.get("validation"))
report["cadenceQualified"] = bool(report["comparisonAccepted"] and all(item["renderEvidence"] for item in included)
    and metadata is not None and not report["validationDiagnostic"]
    and not a.gpu and not metadata.get("gpuTimings") and not metadata.get("cpuStats"))
text = json.dumps(report, indent=2)
if a.output:
    a.output.write_text(text + "\n", encoding="utf-8")
print(text)
if not report["comparisonAccepted"] and not a.diagnostic:
    raise SystemExit("INVALID: selected geometry counts or versions changed/missing; use --diagnostic only for an unaccepted report")
