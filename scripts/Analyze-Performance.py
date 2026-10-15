"""Validate fixed-camera smoke evidence and summarize full-frame cadence, not GPU pass time."""
import argparse
import csv
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("csv", type=Path)
p.add_argument("--output", type=Path)
p.add_argument("--exclude-phases", default="", help="Explicit exclusions, recorded in output; never silently discard drift")
p.add_argument("--diagnostic", action="store_true", help="Report missing/changing geometry versions without accepting a controlled comparison")
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
report = {"kind": "full-frame wall cadence, no validation layers expected", "phases": []}
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
    item["excluded"] = phase in excluded
    report["phases"].append(item)
report["geometryStableAcrossAllPhases"] = all(
    len({tuple(item[k]) for item in report["phases"]}) == 1
    for k in ("nearInstances", "lodSections")) and all(item["geometryStable"] for item in report["phases"])
included = [item for item in report["phases"] if not item["excluded"]]
report["selectedGeometryStable"] = bool(included) and all(item["geometryStable"] for item in included) and all(
    len({tuple(item[k]) for item in included}) == 1 for k in ("nearInstances", "lodSections")) and len({item["nearRadius"] for item in included})==1
report["selectedSceneVersionsStable"] = bool(included) and all(item.get("versionsStable", False) for item in included)
if report["selectedSceneVersionsStable"]:
    report["selectedSceneVersionsStable"] = all(len({tuple(item[k]) for item in included}) == 1 for k in version_keys)
report["comparisonAccepted"] = report["selectedGeometryStable"] and report["selectedSceneVersionsStable"]
text = json.dumps(report, indent=2)
if a.output:
    a.output.write_text(text + "\n", encoding="utf-8")
print(text)
if not report["comparisonAccepted"] and not a.diagnostic:
    raise SystemExit("INVALID: selected geometry counts or versions changed/missing; use --diagnostic only for an unaccepted report")
