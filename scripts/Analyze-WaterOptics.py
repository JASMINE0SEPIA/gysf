"""Diagnose same-camera water radiance before/after RR; 32-spp references are not ground truth."""
import argparse
import importlib.util
import json
from pathlib import Path
import numpy as np

spec = importlib.util.spec_from_file_location("water_guides", Path(__file__).with_name("Analyze-WaterGuides.py"))
guides = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guides)
LUMA = np.array([.2126, .7152, .0722], dtype=np.float32)

def load(folder):
    data = guides.load(folder)
    meta = data["meta"]; request = meta["request"]
    assert request["optics"] and request["phase"].startswith("under-"), "Not an underwater optics capture"
    mode = request["phase"].split("-")[1]
    rr = mode.startswith("rr"); bounces = int(mode[-1]); spp = 1 if rr else 32
    blob = (folder / "world-push.bin").read_bytes()
    def word(name):
        return int(np.frombuffer(blob, "<u4", 1, meta["worldPushOffsets"][name + "_OFFSET"])[0])
    assert word("SPP") == spp == request["spp"], "Wrong actual GPU spp"
    assert word("MAX_BOUNCES") == bounces == request["bounces"], "Wrong actual GPU bounce cap"
    assert bool(word("FEATURE_FLAGS") & 4) == rr, "Unexpected denoising flag"
    assert request["rr"] == rr == meta["resolvedByRr"], "RR did not produce the requested frame"
    assert meta["resolvedFilter"] == ("dlss-rr" if rr else "none"), "Unexpected reconstruction filter"
    resolved = meta["files"]["resolved"]
    assert (resolved["height"], resolved["width"]) == data["position"].shape[:2], "Reference and RR must use native equal resolution"
    post = np.fromfile(folder / resolved["file"], "<f2").astype(np.float32).reshape(resolved["height"], resolved["width"], 4)
    assert np.isfinite(post).all(), "Nonfinite post-reconstruction radiance"
    data["raw"] = data["guides"]["radiance"][..., :3]
    data["resolved"] = post[..., :3]
    if not rr:
        assert np.array_equal(data["raw"], data["resolved"]), "Raw control was modified by another post filter"
    data["water"] = (data["kind"] == 2) & guides.fixture_roi(data)
    assert data["water"].sum() > 1000, "Insufficient pool interface pixels"
    cosine = np.abs(np.sum(data["normal"] * data["rays"], axis=-1))
    data["tir"] = data["water"] & (1.333**2 * np.maximum(0, 1 - cosine**2) > 1)
    data["rawLum"] = data["raw"] @ LUMA; data["resolvedLum"] = data["resolved"] @ LUMA
    return data

def distribution(image, mask):
    values = image[mask]
    result = guides.stats(values)
    result["below1e-5"] = int((values < 1e-5).sum())
    result["below1e-3"] = int((values < 1e-3).sum())
    result["negative"] = int((values < 0).sum())
    return result

def summarize(data):
    result = {"pixels": int(data["water"].sum()), "lodPixels": int((data["water"] & ((data["flags"] & 4096) != 0)).sum()),
              "tirPixels": int(data["tir"].sum()), "raw": {}, "resolved": {}, "distanceBands": []}
    for name in ("water", "tir"):
        for source in ("raw", "resolved"):
            result[source][name] = distribution(data[source + "Lum"], data[name])
    distance = np.linalg.norm(data["position"], axis=-1)
    for lo, hi in zip((0, 4, 8, 16, 32, 64, 128), (4, 8, 16, 32, 64, 128, 1e6)):
        mask = data["water"] & (distance >= lo) & (distance < hi)
        result["distanceBands"].append({"lower": lo, "upper": hi, "raw": distribution(data["rawLum"], mask),
                                        "resolved": distribution(data["resolvedLum"], mask)})
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path); parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    results = {"scope": "Static native-resolution pool diagnosis; excludes FPS and unbiased lighting acceptance", "strengths": {}}
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(2, 4, figsize=(16, 8), constrained_layout=True)
    profiles = ["strength,mode,displayRow,pixels,rawMean,resolvedMean"]
    for row, strength in enumerate((1, 4)):
        modes = {mode: load(args.input / f"under-{mode}-{strength}") for mode in ("raw4", "raw8", "rr4", "rr8")}
        baseline = modes["raw8"]; shared = baseline["water"].copy()
        for data in modes.values():
            assert np.array_equal(data["camera"], baseline["camera"]), "Cameras differ"
            assert np.max(np.linalg.norm(data["rays"] - baseline["rays"], axis=-1)) < 1e-7, "Pixel rays differ"
            assert np.array_equal(data["kind"][baseline["water"]], baseline["kind"][baseline["water"]]), "Pool material kinds changed across controls"
            assert np.max(np.linalg.norm(data["position"][baseline["water"]] - baseline["position"][baseline["water"]], axis=-1)) < .002, "Water geometry changed"
            shared &= data["water"]
        metrics = {mode: summarize(data) for mode, data in modes.items()}
        ref = baseline["rawLum"]
        metrics["comparisons"] = {"sharedPixels": int(shared.sum()), "raw4BlackWhereRaw8Lit": int((shared & (modes["raw4"]["rawLum"] < 1e-5) & (ref > .01)).sum())}
        for mode in ("rr4", "rr8"):
            source = modes["raw" + mode[-1]]["rawLum"]
            metrics["comparisons"][mode + "BlackWhereRawLit"] = int((shared & (modes[mode]["resolvedLum"] < 1e-5) & (source > .01)).sum())
        metrics["grossBlackRegionGate"] = bool(metrics["comparisons"]["rr4BlackWhereRawLit"] == 0
            and metrics["comparisons"]["rr8BlackWhereRawLit"] == 0
            and metrics["raw8"]["raw"]["water"]["below1e-3"] < .01 * metrics["raw8"]["pixels"])
        results["strengths"][str(strength)] = metrics
        for column, (mode, data) in enumerate(modes.items()):
            shown = data["resolvedLum"] if mode.startswith("rr") else data["rawLum"]
            im = axes[row, column].imshow(np.log2(np.maximum(shown, 2**-16)), origin="lower", vmin=-10, vmax=2, cmap="magma")
            axes[row, column].set_title(f"Strength {strength}: {mode}, log2 linear luminance"); axes[row, column].set_axis_off()
            for y in range(shown.shape[0]):
                mask = data["water"][y]
                if mask.any():
                    profiles.append(f"{strength},{mode},{shown.shape[0]-1-y},{mask.sum()},{data['rawLum'][y][mask].mean():.9g},{data['resolvedLum'][y][mask].mean():.9g}")
    fig.colorbar(im, ax=axes, shrink=.7); fig.savefig(args.output / "luminance.png", dpi=120); plt.close(fig)
    (args.output / "profiles.csv").write_text("\n".join(profiles) + "\n", encoding="utf-8")
    (args.output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps(results, indent=2))
    if not all(row["grossBlackRegionGate"] for row in results["strengths"].values()):
        raise SystemExit("Gross black-region gate failed; this does not measure natural shoreline quality")

if __name__ == "__main__":
    main()
