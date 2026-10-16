"""Compare unfiltered GPU interface and denoiser guides; screenshots are illustrative only."""
import argparse
import json
from pathlib import Path
import numpy as np

PHASES = ("above-1", "above-4", "under-1", "under-4")
FORMATS = {"rgba16f": ("<f2", 4), "rg16f": ("<f2", 2), "r32f": ("<f4", 1)}

def stats(values):
    v = np.asarray(values).ravel()
    v = v[np.isfinite(v)]
    if not len(v):
        return {"count": 0}
    return {"count": int(len(v)), "max": float(np.max(v)), "mean": float(np.mean(v)),
            "p95": float(np.quantile(v, .95)), "p99": float(np.quantile(v, .99)),
            "p999": float(np.quantile(v, .999))}

def load(folder):
    meta = json.loads((folder / "capture.json").read_text(encoding="utf-8-sig"))
    h, w = meta["height"], meta["width"]
    surface = np.fromfile(folder / meta["surfaceFile"], "<f4").reshape(h, w, 4, 4)
    assert np.isfinite(surface).all(), f"Nonfinite interface readback: {folder}"
    guides = {}
    for name, desc in meta["files"].items():
        path = folder / desc["file"]
        assert path.stat().st_size == desc["bytes"], f"Truncated {path}"
        if desc["format"] in FORMATS:
            dtype, channels = FORMATS[desc["format"]]
            guides[name] = np.fromfile(path, dtype).astype(np.float32).reshape(h, w, channels)
            assert np.isfinite(guides[name]).all(), f"Nonfinite {path}"
    blob = (folder / meta["files"]["world-push"]["file"]).read_bytes()
    def world(name, count, dtype="<f4"):
        return np.frombuffer(blob, dtype=dtype, count=count, offset=meta["worldPushOffsets"][name + "_OFFSET"])
    assert np.all(world("JITTER", 2) == 0), "Primary jitter was not suppressed"
    tuning = world("WATER_OPACITY", 4)
    assert tuning[2] == 0, f"Static-wave capture was animated: {folder} speed={tuning[2]}"
    assert tuning[1] == int(meta["request"]["phase"].split("-")[-1]), "Wrong wave strength"
    camera = np.asarray(meta["frameState"]["camera"])
    expected = np.asarray(meta["request"]["expectedEye"])
    assert np.max(np.abs(camera - expected)) < 1e-5, f"Effective eye changed: {camera} != {expected}"
    submerged = bool(world("WATER_CAMERA", 4, "<u4")[0] & 1)
    assert submerged == meta["request"]["phase"].startswith("under"), "Wrong GPU camera medium"
    assert np.all(world("CAM_DELTA", 3) == 0), "Camera was moving at capture"
    assert np.array_equal(world("CUR_VIEW_PROJ", 16), world("PREV_VIEW_PROJ", 16)), "Projection history was moving"
    assert (folder / "field-current.bin").read_bytes() == (folder / "field-previous.bin").read_bytes(), "Field history was changing"
    inv = world("INV_VIEW_PROJ", 16).reshape(4, 4, order="F").astype(np.float64)
    yy, xx = np.mgrid[:h, :w]
    near = np.stack(((xx + .5) / w * 2 - 1, (yy + .5) / h * 2 - 1, np.ones_like(xx), np.ones_like(xx)), axis=-1)
    far = near.copy(); far[..., 2] = 0
    near = near @ inv.T; far = far @ inv.T
    rays = far[..., :3] / far[..., 3:] - near[..., :3] / near[..., 3:]
    rays /= np.linalg.norm(rays, axis=-1, keepdims=True)
    return {"meta": meta, "surface": surface, "guides": guides, "rays": rays,
            "position": surface[..., 0, :3], "normal": surface[..., 1, :3],
            "flags": np.rint(surface[..., 0, 3]).astype(np.uint32),
            "kind": np.rint(surface[..., 2, 3]).astype(np.uint32), "camera": camera}

def fixture_roi(data, margin=0):
    p = data["position"] + data["camera"]
    varied = data["meta"]["request"].get("varied", False)
    return ((p[..., 0] > 48 + margin) & (p[..., 0] < 80 - margin)
            & (p[..., 2] > 16 + margin) & (p[..., 2] < 145 - margin)
            & (p[..., 1] > (109 if varied else 111.75)) & (p[..., 1] < (116.2 if varied else 114.1)))

def single(data):
    water = (data["kind"] == 2) & fixture_roi(data)
    assert water.sum() > 100, "Fixture water is missing from the raw interface image"
    lod = water & ((data["flags"] & 4096) != 0)
    assert ((data["flags"][water] & 2048) != 0).all(), "Non-procedural fixture water"
    if data["meta"]["request"]["nearReference"]:
        assert lod.sum() == 0, "Near-only fixture still contains LOD water"
    else:
        assert lod.sum() > 100 and (water & ~lod).sum() > 100, "Mixed fixture lacks both owners"
    surf = data["surface"]
    current = surf[..., 1, :3][water]; previous = surf[..., 2, :3][water]
    angular = np.linalg.norm(current / np.linalg.norm(current, axis=-1, keepdims=True)
                             - previous / np.linalg.norm(previous, axis=-1, keepdims=True), axis=-1)
    result = {"waterPixels": int(water.sum()), "lodPixels": int(lod.sum()),
            "staticPhysicalMotion": stats(np.linalg.norm(surf[..., 3, :3][water], axis=-1)),
            "staticNormalHistory": stats(np.linalg.norm(surf[..., 1, :3][water] - surf[..., 2, :3][water], axis=-1)),
            "staticNormalDirectionHistory": stats(angular),
            "interfaceNormalLengthError": stats(np.abs(np.linalg.norm(current, axis=-1) - 1)),
            "ordinaryMotionPixels": stats(np.linalg.norm(data["guides"]["motion"][water], axis=-1)),
            "specularMotionPixels": stats(np.linalg.norm(data["guides"]["spec-motion"][water], axis=-1))}
    result["staticHistoryGate"] = bool(result["staticNormalHistory"]["max"] < 1e-7
                                      and result["staticPhysicalMotion"]["max"] < 1e-7
                                      and result["interfaceNormalLengthError"]["max"] < 1e-6
                                      and result["specularMotionPixels"]["max"] < .001)
    if data["meta"]["request"].get("varied", False):
        assert data["meta"]["request"]["particles"] is False and not (data["kind"] == 3).any(), "Particles contaminate the fixed geometry comparison"
        p = data["position"] + data["camera"]
        step = water & (np.abs(p[..., 2]-80) < .001) & (np.abs(data["normal"][..., 1]) < 1e-6)
        solid = ((data["kind"] == 1) & (p[..., 0] >= 54) & (p[..., 0] <= 60)
                 & (p[..., 2] >= 60) & (p[..., 2] <= 68) & (p[..., 1] >= 112) & (p[..., 1] <= 113.001))
        glass = (data["kind"] == 4) & fixture_roi(data)  # MATERIAL_DIELECTRIC (3) + 1
        result["variedCoverage"] = {"stepSidePixels": int(step.sum()), "solidCoverPixels": int(solid.sum()),
                                    "glassCoverPixels": int(glass.sum()), "lowerSurfacePixels": int((water & (p[..., 2] < 79)).sum()),
                                    "upperSurfacePixels": int((water & (p[..., 2] > 81)).sum())}
    return result

def compare(mixed, reference, output, phase):
    assert mixed["meta"]["request"].get("varied", False) == reference["meta"]["request"].get("varied", False), "Fixture modes differ"
    assert mixed["position"].shape == reference["position"].shape, "Render extents differ"
    assert np.max(np.abs(mixed["camera"] - reference["camera"])) < 1e-6, "Cameras differ"
    ray_error = np.linalg.norm(mixed["rays"] - reference["rays"], axis=-1)
    assert ray_error.max() < 1e-5, "Pixel ray directions differ"
    wanted = (reference["kind"] == 2) & fixture_roi(reference, 1)
    shared = wanted & (mixed["kind"] == 2) & fixture_roi(mixed)
    conditioned = shared & (np.abs(np.sum(reference["normal"] * reference["rays"], axis=-1)) > .15)
    pos_error = np.linalg.norm(mixed["position"] - reference["position"], axis=-1)
    normal_error = np.linalg.norm(mixed["normal"] - reference["normal"], axis=-1)
    result = {"rayDirectionError": stats(ray_error), "referenceWaterInteriorPixels": int(wanted.sum()),
              "materialMismatchPixels": int((wanted & (mixed["kind"] != 2)).sum()),
              "missingInteriorPixels": int((wanted & ~shared).sum()),
              "interfacePositionBlocks": stats(pos_error[shared]), "interfaceNormalVector": stats(normal_error[shared]),
              "conditionedPositionBlocks": stats(pos_error[conditioned]),
              "conditionedNormalVector": stats(normal_error[conditioned]), "guides": {}}
    for name in mixed["guides"]:
        result["guides"][name] = stats(np.linalg.norm(mixed["guides"][name] - reference["guides"][name], axis=-1)[shared])
    result["ordinaryGuideNormalVector"] = stats(np.linalg.norm(mixed["guides"]["normal"][..., :3]
                                                               - reference["guides"]["normal"][..., :3], axis=-1)[shared])
    result["ordinaryGuideRoughness"] = stats(np.abs(mixed["guides"]["normal"][..., 3]
                                                   - reference["guides"]["normal"][..., 3])[shared])
    result["conditionedGeometryGate"] = bool(conditioned.sum() > 100 and np.quantile(pos_error[conditioned], .99) < .002
                                              and np.quantile(normal_error[conditioned], .99) < .002
                                              and result["materialMismatchPixels"] == 0)
    result["ordinaryMaterialGuideGate"] = bool(result["ordinaryGuideNormalVector"]["max"] < .002
                                              and result["ordinaryGuideRoughness"]["max"] < .002)
    result["byOwner"] = {}
    for owner, is_lod in (("near", False), ("lod", True)):
        owned = shared & (((mixed["flags"] & 4096) != 0) == is_lod)
        result["byOwner"][owner] = {"positionBlocks": stats(pos_error[owned]), "normalVector": stats(normal_error[owned])}
    result["allInterfaceGeometryGate"] = bool(result["missingInteriorPixels"] == 0
        and all(row["positionBlocks"]["count"] > 100 and row["positionBlocks"]["max"] < .002
                and row["normalVector"]["max"] < .002 for row in result["byOwner"].values())
        and result["guides"]["view-z"]["max"] < .002)
    if reference["meta"]["request"].get("varied", False):
        foreground = (reference["kind"] > 0) & fixture_roi(reference, 1)
        matched = foreground & (mixed["kind"] == reference["kind"])
        nonwater = matched & (reference["kind"] != 2)
        result["variedForeground"] = {"pixels": int(foreground.sum()), "materialMismatchPixels": int((foreground & ~matched).sum()),
                                       "nonWaterPositionBlocks": stats(pos_error[nonwater]), "nonWaterNormalVector": stats(normal_error[nonwater])}
        result["variedForegroundGate"] = bool((foreground & ~matched).sum() == 0 and
            (not nonwater.any() or (pos_error[nonwater].max() < .002 and normal_error[nonwater].max() < .002)))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(2, 3, figsize=(15, 8), constrained_layout=True)
    axes[0, 0].imshow(mixed["normal"] * .5 + .5); axes[0, 0].set_title("Mixed: physical interface normal")
    axes[0, 1].imshow(reference["normal"] * .5 + .5); axes[0, 1].set_title("Near only: physical interface normal")
    maps = ((pos_error, "Interface position error (blocks)", axes[0, 2]),
            (normal_error, "Interface normal vector error", axes[1, 0]),
            (np.abs(mixed["guides"]["view-z"][..., 0] - reference["guides"]["view-z"][..., 0]), "Transmitted view-Z error (blocks)", axes[1, 1]),
            (np.linalg.norm(mixed["guides"]["albedo"] - reference["guides"]["albedo"], axis=-1), "Ordinary albedo vector error", axes[1, 2]))
    for values, title, axis in maps:
        shown = np.ma.masked_where(~shared, np.log10(np.maximum(values, 1e-7)))
        im = axis.imshow(shown, vmin=-7, vmax=0, cmap="magma"); axis.set_title(title + " (log10)"); fig.colorbar(im, ax=axis)
    for axis in axes.flat: axis.set_axis_off()
    fig.suptitle(phase + ": fixed eye, zero jitter, static waves; raw GPU data")
    fig.savefig(output / (phase + ".png"), dpi=120); plt.close(fig)
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mixed", type=Path); parser.add_argument("reference", type=Path, nargs="?")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--phases", nargs="+", choices=PHASES, default=PHASES)
    parser.add_argument("--require-static", action="store_true", help="Require zero normal/physical history, unit normals and all reflection motion below .001 pixels")
    parser.add_argument("--require-material", action="store_true", help="Require all ordinary normal/roughness guide errors below .002 in the clean quartz pool")
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    results = {"scope": "Raw GPU interfaces and denoiser guides; excludes screenshot/I/O time and FPS acceptance", "phases": {}}
    for phase in args.phases:
        a = load(args.mixed / phase); row = {"mixed": single(a)}
        if args.reference:
            b = load(args.reference / phase); row["reference"] = single(b)
            row["comparison"] = compare(a, b, args.output, phase)
        results["phases"][phase] = row
    (args.output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps(results, indent=2))
    varied = [row for row in results["phases"].values() if "variedCoverage" in row["mixed"]]
    if varied:
        for owner in ("mixed", "reference") if args.reference else ("mixed",):
            for key in ("stepSidePixels", "solidCoverPixels", "glassCoverPixels", "lowerSurfacePixels", "upperSurfacePixels"):
                if sum(row[owner]["variedCoverage"][key] for row in varied) <= 20:
                    raise SystemExit(f"Varied source not exercised in raw RT interfaces: {owner} {key}")
        if args.reference and not all(row["comparison"]["variedForegroundGate"] for row in varied):
            raise SystemExit("Varied foreground material/geometry mismatch")
    if args.require_static and not all(row[owner]["staticHistoryGate"] for row in results["phases"].values()
                                      for owner in ("mixed", "reference") if owner in row):
        raise SystemExit("Static-water history gate failed; inspect raw normal/physical/reflection motion")
    if args.require_material and (not args.reference or not all(row["comparison"]["ordinaryMaterialGuideGate"]
                                                               for row in results["phases"].values())):
        raise SystemExit("Matched ordinary material guide gate failed; inspect normal and roughness channels")
    if args.reference and not all(row["comparison"]["conditionedGeometryGate"] and row["comparison"]["allInterfaceGeometryGate"]
                                  for row in results["phases"].values()):
        raise SystemExit("Mixed/near-only geometry gate failed; inspect the numerical differences")

if __name__ == "__main__":
    main()
