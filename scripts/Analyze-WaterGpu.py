"""Cross-check GPU hit distances against the independently sampled CPU nearest-root table."""
import argparse
import csv
import json
import math
from pathlib import Path


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("reference",type=Path)
    parser.add_argument("directory",type=Path)
    parser.add_argument("--finite-patch",action="store_true")
    args=parser.parse_args()
    with args.reference.open(newline="",encoding="utf-8") as file:
        reference_rows=list(csv.DictReader(file))
        reference={int(row["index"]):row for row in reference_rows}
    if len(reference_rows)!=4096 or set(reference)!=set(range(4096)):
        raise ValueError("Incomplete CPU reference table")
    if any(float(row["reference_disagreement"])!=0 or not math.isfinite(float(row["reference_t"]))
           or (not args.finite_patch and float(row["reference_t"])<0) for row in reference.values()):
        raise ValueError("CPU reference is unresolved")
    summary=[]
    for variant in ("production","candidate"):
        for mode in ("coherent","shuffled"):
            with (args.directory/f"{variant}-{mode}.csv").open(newline="",encoding="utf-8") as file:
                rows=list(csv.DictReader(file))
            if len(rows)!=4096 or {int(row["index"]) for row in rows}!=set(range(4096)):
                raise ValueError("Incomplete GPU readback table")
            failures=0;maximum=0.0;worst=None;missing=0;false_hits=0;hits=0
            for row in rows:
                index=int(row["index"])
                case=(index*757)&4095 if mode=="shuffled" else index
                actual=float(row["hit_t"]);expected=float(reference[case]["reference_t"])
                if not math.isfinite(actual):
                    failures+=1;continue
                if actual<0 or expected<0:
                    if actual<0<=expected:missing+=1;failures+=1
                    if expected<0<=actual:false_hits+=1;failures+=1
                    continue
                hits+=1
                error=abs(actual-expected)
                if not math.isfinite(error) or error>0.02:failures+=1
                if error>maximum:maximum=error;worst=case
            summary.append({"variant":variant,"layout":mode,"cases":len(rows),"distanceToleranceBlocks":0.02,
                "failures":failures,"matchedHits":hits,"missingHits":missing,"falseHits":false_hits,
                "maxDistanceErrorBlocks":maximum,"worstCase":worst})
    (args.directory/"nearest-root-check.json").write_text(json.dumps(summary,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(summary))
    if any(item["failures"] for item in summary if item["variant"]=="candidate"):
        raise SystemExit(1)


if __name__=="__main__":main()
