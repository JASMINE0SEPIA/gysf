"""Corrupt real cadence evidence to check cache stability gates; this is not a GPU test."""
import argparse
import copy
import csv
import json
import subprocess
import sys
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("csv", type=Path, help="A valid single-phase runtime-cache capture")
p.add_argument("--output", type=Path, default=Path("build/performance-cache-evidence"))
a = p.parse_args()
with a.csv.open(newline="", encoding="utf-8-sig") as f:
    rows = list(csv.DictReader(f))
metadata = json.loads((a.csv.parent / "run.json").read_text(encoding="utf-8-sig"))
metadata["waterBoundsCache"] = True
cache_keys = [k for k in rows[0] if k.startswith("waterCache")]
if len({r["phase"] for r in rows}) != 1 or len(cache_keys) != 7:
    raise SystemExit("Use a single-phase capture with complete cache evidence")
cases = []
def case(name, reject, mutate=lambda r, m: None):
    data, meta = copy.deepcopy(rows), copy.deepcopy(metadata)
    mutate(data, meta)
    folder = a.output / name
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / "frames.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(data[0]))
        writer.writeheader()
        writer.writerows(data)
    (folder / "run.json").write_text(json.dumps(meta), encoding="utf-8")
    result = subprocess.run([sys.executable, str(Path(__file__).with_name("Analyze-Performance.py")), str(path)],
                            capture_output=True, text=True, encoding="utf-8")
    if (result.returncode != 0) != reject:
        raise SystemExit(f"Cache evidence gate failed: {name}\n{result.stdout}\n{result.stderr}")
    cases.append({"case": name, "rejected": bool(result.returncode), "reason": result.stderr.strip()})

def constant(key, value):
    return lambda r, m: [row.update({key: str(value)}) for row in r]

case("valid", False)
case("pending", True, constant("waterCachePending", 1))
case("eligible-misses", True, constant("waterCacheMisses", 1))
case("unexercised", True, constant("waterCacheHits", 0))
case("no-ready", True, constant("waterCacheReady", 0))
case("negative-counter", True, constant("waterCacheInputDeferrals", -1))
case("publication-drift", True, lambda r, m: r[0].update(waterCachePublished="999999"))
case("incomplete", True, lambda r, m: [row.pop("waterCacheSources") for row in r])
case("missing-requested-evidence", True, lambda r, m: [[row.pop(k) for k in cache_keys] for row in r])
def legacy(r, m):
    for row in r:
        for key in cache_keys:
            row.pop(key)
    m.pop("waterBoundsCache", None)
case("legacy-no-cache", False, legacy)
def phases(r, m):
    extra = copy.deepcopy(r)
    for row in extra:
        row["phase"] = "1"
        row["waterCachePublished"] = str(int(row["waterCachePublished"]) + 1)
    r.extend(extra)
    m["presets"] = "0,0"
case("between-phase-publication", True, phases)
(a.output / "results.json").write_text(json.dumps(cases, indent=2) + "\n", encoding="utf-8")
print(f"Cache cadence evidence checker: {len(cases)} cases passed")
