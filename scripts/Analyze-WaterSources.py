"""Inspect real exported top edges; side faces and live ownership are outside this diagnostic."""
import argparse
import itertools
import json
import math
import hashlib
import shutil
from collections import defaultdict
from pathlib import Path
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--probe-input", type=Path, help="Export unchanged actual triangle pairs with independent source references")
    args = parser.parse_args()
    meta = json.loads((args.input / "capture.json").read_text(encoding="utf-8-sig"))
    assert meta["kind"] == "actual-provider-mips" and meta["cases"] == 4096
    packets = np.fromfile(args.input / "water-layer-primitives.bin", "<f4").reshape(4096, 3, 20)
    assert np.isfinite(packets).all()
    count = meta["sourceQuads"]
    assert 0 < count <= 4096
    quads = []
    for index, pair in enumerate(packets[:count, :2]):
        points = {tuple(map(float, tri[4*v:4*v+3])) for tri in pair for v in range(3)}
        assert len(points) == 4
        x0, z0, width, depth = map(float, pair[0, 12:16])
        heights = {(x, z): y for x, y, z in points}
        h = [heights[x, z] for x, z in ((x0, z0), (x0, z0+depth), (x0+width, z0), (x0+width, z0+depth))]
        quads.append(dict(index=index, level=int(packets[index, 2, 6]), x=x0, z=z0, width=width, depth=depth,
                          heights=h, tag=int(pair[0, 3]), oldLayer=int(np.floor(np.nextafter(np.float32(max(h)), np.float32(-np.inf))))+meta["anchorY"]))
    for c in range(4096):
        assert np.array_equal(packets[c, :2], packets[c % count, :2]), "Source geometry changed between samples"

    words = np.fromfile(args.input / "water-field-current.bin", "<u4")
    assert words[2] == 1 and words[3] == len(words)
    tiles = {}
    for entry in words[4:4+(int(words[0])+1)*8].reshape(-1, 8):
        if not entry[3]:
            continue
        key = tuple(map(int, entry[:3].view("<i4")))
        assert key not in tiles
        start, size = int(entry[3]), 1 if entry[4] else 256
        assert start + size <= len(words)
        tiles[key] = words[start:start+size]
    assert len(tiles) == meta["tiles"]

    def layer(q, x, z):
        u = min(max((x-q["x"])/q["width"], 0), 1)
        v = min(max((z-q["z"])/q["depth"], 0), 1)
        h = q["heights"]
        y = (1-u)*((1-v)*h[0]+v*h[1])+u*((1-v)*h[2]+v*h[3])
        return math.ceil(y-.001)-1+meta["anchorY"]

    def clearance(x, y, z):
        tile = tiles.get((x//16, y, z//16))
        if tile is None:
            return None
        word = int(tile[0 if len(tile) == 1 else (z % 16)*16+x % 16])
        return (word & 65535)*(3/65535) if word & 65536 else 0

    groups = defaultdict(list)
    for q in quads:
        x, z, w, d = q["x"], q["z"], q["width"], q["depth"]
        h = q["heights"]
        for axis, fixed, lo, hi, a, b, side in ((0,x,z,z+d,h[0],h[1],1), (0,x+w,z,z+d,h[2],h[3],-1),
                                               (2,z,x,x+w,h[0],h[2],1), (2,z+d,x,x+w,h[1],h[3],-1)):
            groups[axis, fixed].append(dict(q=q, lo=lo, hi=hi, a=a, b=b, side=side))
    summary = {}
    worst = []
    coincident_pairs = []
    for name, delta in (("sameMip", 0), ("adjacentMip", 1)):
        row = dict(candidatePairs=0, noncoincidentRestPairs=0, coincidentRestPairs=0, exactRestPairs=0,
                   maxRestGapBlocks=0, coincidentNodes=0, sourceLayerMismatches=0, weightMismatches=0,
                   maxWeightGap=0, oldWeightMismatches=0, oldMaxWeightGap=0, oldUnrequestedNodePairs=0)
        for (axis, fixed), edges in groups.items():
            for a, b in itertools.combinations(edges, 2):
                if a["side"] == b["side"] or abs(a["q"]["level"]-b["q"]["level"]) != delta:
                    continue
                lo, hi = max(a["lo"], b["lo"]), min(a["hi"], b["hi"])
                if hi <= lo:
                    continue
                def height(edge, p):
                    return edge["a"]+(edge["b"]-edge["a"])*(p-edge["lo"])/(edge["hi"]-edge["lo"])
                gap = max(abs(height(a, p)-height(b, p)) for p in (lo, hi))
                row["candidatePairs"] += 1
                row["maxRestGapBlocks"] = max(row["maxRestGapBlocks"], gap)
                if gap > .0003:
                    row["noncoincidentRestPairs"] += 1
                    if len(worst) < 20:
                        worst.append(dict(group=name, a=a["q"]["index"], b=b["q"]["index"], restGap=gap))
                    continue
                row["coincidentRestPairs"] += 1
                coincident_pairs.append((axis, fixed, lo, hi, a, b))
                row["exactRestPairs"] += gap <= .000001
                for p in range(math.ceil(lo), math.floor(hi)+1):
                    x, z = (int(fixed), p) if axis == 0 else (p, int(fixed))
                    ya, yb = layer(a["q"], x, z), layer(b["q"], x, z)
                    ca, cb = clearance(x, ya, z), clearance(x, yb, z)
                    assert ca is not None and cb is not None, "Missing current node owner"
                    row["coincidentNodes"] += 1
                    row["sourceLayerMismatches"] += ya != yb
                    difference = max(abs(min(ca/(.55*s), 1)-min(cb/(.55*s), 1)) for s in (1,4))
                    row["weightMismatches"] += difference > .000001
                    row["maxWeightGap"] = max(row["maxWeightGap"], difference)
                    oa, ob = clearance(x, a["q"]["oldLayer"], z), clearance(x, b["q"]["oldLayer"], z)
                    if oa is None or ob is None:
                        row["oldUnrequestedNodePairs"] += 1
                    else:
                        old = max(abs(min(oa/(.55*s), 1)-min(ob/(.55*s), 1)) for s in (1,4))
                        row["oldWeightMismatches"] += old > .000001
                        row["oldMaxWeightGap"] = max(row["oldMaxWeightGap"], old)
        row["coincidentSourceGate"] = bool(row["coincidentNodes"] > 0 and row["weightMismatches"] == 0)
        summary[name] = row
    report = dict(scope="Captured top-edge candidates; no side-face or active-ownership test. Noncoincident rest edges are not automatically cracks.",
                  sourceQuads=count, groups=summary, noncoincidentExamples=worst,
                  interQuadOpticsQualified=False, sourcePolicyGate=all(v["coincidentSourceGate"] for v in summary.values()))
    args.output.write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if not report["sourcePolicyGate"]:
        raise SystemExit("Captured coincident-edge source policy needs investigation")
    if args.probe_input:
        args.probe_input.mkdir(parents=True, exist_ok=False)
        def weights(q, x, z, strength):
            cx, cz = math.floor(x), math.floor(z)
            values = []
            for dx, dz in ((0,0), (0,1), (1,0), (1,1)):
                value = clearance(cx+dx, layer(q, cx+dx, cz+dz), cz+dz)
                assert value is not None
                values.append(min(value/(.55*strength), 1))
            u, v = x-cx, z-cz
            u, v = u*u*(3-2*u), v*v*(3-2*v)
            return (1-u)*((1-v)*values[0]+v*values[1])+u*((1-v)*values[2]+v*values[3])
        def triangle(q, x, z):
            for tri in packets[q["index"], :2]:
                verts = tri[[0,2,4,6,8,10]].reshape(3,2).astype(float)
                uv = np.linalg.solve((verts[1:]-verts[0]).T, np.array([x,z])-verts[0])
                if min(uv) >= -1e-9 and sum(uv) <= 1+1e-9:
                    return tri
            raise AssertionError("Captured edge point is outside both source triangles")
        data = np.zeros((4096, 3, 20), dtype="<f4")
        levels = [0]*5
        edge_cases = varying = zero = wet = partial = 0
        total = len(coincident_pairs)+count
        for c in range(4096):
            n, t = c % total, (1+(c//total) % 7)/8
            if n < len(coincident_pairs):
                axis, fixed, lo, hi, a, b = coincident_pairs[n]
                p = lo+(hi-lo)*t
                x, z = (fixed,p) if axis == 0 else (p,fixed)
                qa, qb = a["q"], b["q"]
                ya = a["a"]+(a["b"]-a["a"])*(p-a["lo"])/(a["hi"]-a["lo"])
                yb = b["a"]+(b["b"]-b["a"])*(p-b["lo"])/(b["hi"]-b["lo"])
                data[c,0], data[c,1] = triangle(qa,x,z), triangle(qb,x,z)
                edge_cases += 1
            else:
                qa = qb = quads[n-len(coincident_pairs)]
                data[c,:2] = packets[qa["index"],:2]
                a, b = data[c,0, :3], data[c,0, 8:11]
                x, ya, z = (a+(b-a)*t).astype(float)
                yb = ya
            wa = [weights(qa,x,z,s) for s in (1,4)]
            wb = [weights(qb,x,z,s) for s in (1,4)]
            assert max(abs(a-b) for a,b in zip(wa,wb)) < 1e-6
            data[c,2,:4] = x,ya,z,yb
            data[c,2,4:8] = wa[0],wa[1],max(qa["level"],qb["level"]),1
            data[c,2,8:10] = wb
            w = wa[c&1]
            zero += w == 0
            wet += w == 1
            partial += 0 < w < 1
            varying += data[c,0,3] == 2 or data[c,1,3] == 2
            levels[int(data[c,2,6])] += 1
        assert edge_cases and zero and wet and partial and varying and min(levels) > 0
        data.tofile(args.probe_input / "water-layer-primitives.bin")
        for name in ("water-field-current.bin", "water-field-previous.bin", "water-field-full-cell.bin"):
            shutil.copyfile(args.input/name, args.probe_input/name)
        output_meta = dict(kind="actual-provider-edge-pairs", cases=4096, anchorY=meta["anchorY"], levels=levels,
                           sourceQuads=count, edgePairCases=edge_cases, internalCases=4096-edge_cases,
                           varyingCases=int(varying), zeroCases=int(zero), wetCases=int(wet), partialCases=int(partial),
                           knownNodes=meta["knownNodes"], unknownNodes=meta["unknownNodes"], knownZeroNodes=meta["knownZeroNodes"], unknownAnchorXZ=meta["unknownAnchorXZ"],
                           sourceCaptureSha256=hashlib.sha256((args.input/"capture.json").read_bytes()).hexdigest(),
                           restCoincidenceToleranceBlocks=.0003,
                           scope="Unchanged real triangle packets; independent references; wave gaps exclude recorded rest-height differences; no live ownership/optics acceptance")
        (args.probe_input/"capture.json").write_text(json.dumps(output_meta,indent=2)+"\n",encoding="utf-8")


if __name__ == "__main__":
    main()
