"""Summarize real rendered-frame cadence; generated presentations are not counted as real frames."""
import argparse
import csv
import json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("runs", nargs="+", type=Path, help="Physical replay run directories")
args = p.parse_args()
results = {}
for run in args.runs:
    with (run / "motion-frames.csv").open(newline="") as source:
        rows = list(csv.DictReader(source))
    phases = {}
    for phase, label in enumerate(("stationary", "turn", "move", "move-and-turn")):
        selected = [row for row in rows if int(row["phase"]) == phase]
        if len(selected) < 100:
            raise ValueError(f"Insufficient frames: {run}, {label}")
        frames = [int(row["rtFrame"]) for row in selected]
        if any(b <= a for a, b in zip(frames, frames[1:])):
            raise ValueError(f"Repeated or reversed rendered frames: {run}, {label}")
        intervals = sorted(float(row["intervalMs"]) for row in selected)
        if intervals[0] <= 0:
            raise ValueError(f"Nonpositive frame interval: {run}, {label}")
        duration = sum(intervals) / 1000
        phases[label] = {
            "frames": len(intervals), "measuredSeconds": round(duration, 3),
            **{name: round(intervals[min(len(intervals)-1, int(len(intervals)*q))], 3)
               for name, q in (("medianMs", .5), ("p95Ms", .95), ("p99Ms", .99), ("maxMs", 1))},
            "over50Ms": sum(x > 50 for x in intervals),
            "over100Ms": sum(x > 100 for x in intervals),
            "over200Ms": sum(x > 200 for x in intervals),
        }
    state = json.loads((run / "motion-results.json").read_text())
    final = state["final"]
    if final["resident"] <= 0 or final["streaming"]["remaining"] or final["streaming"]["inFlight"]:
        raise ValueError(f"Replay did not finish reconstruction: {run}")
    if final["streaming"]["viewCancellations"]:
        raise ValueError(f"Replay cancelled camera work: {run}")
    results[run.name] = {"cadence": phases, "final": final}
print(json.dumps(results, indent=2))
