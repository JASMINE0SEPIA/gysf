# Coincident water edges: source-layer discontinuity

This is a reproduced defect in production source `0212c2e`, not a repair or a claim that the
player's entire scene is explained. Finite water stays off by default. No PCL file was modified.

`RtWaterGeometry.pack` currently stores one source layer for an entire water quad: the integer
layer below its highest rest vertex. `water_primitive.slang` uses that layer for the canonical
field lookup throughout the face. Two geometrically coincident edges can therefore query different
water layers if their other vertices reach different elevations. A known wet low surface can
displace while the neighboring coarse sloped surface reads an unknown higher layer and stays flat.
The field's unit-cell interpolation cannot repair inconsistent layer selection before lookup.

## Reproduction through production data

`WaterLayerInputs` calls the actual Java packer and exports two triangles sharing an exact rest
edge. One side is a one-block flat top at Y=175+8/9; its neighbor rises to 176.25 across 1, 2, 4,
8 or 16 blocks. Inputs include both quad diagonals and Y anchors -128/0/128. The production Java
field fixture has known fully wet clearance at layer 175 and explicit absent/unknown layer 176.
All 4,096 cases use the production Slang intersection functions, strength one/four and multiple
times/edge positions. No renderer shader source is patched by the test.

Two independent controls require coincident intersections with waves disabled and with the
canonical field absent/fully wet. The continuity gate additionally requires that **both sides**
retain the analytic full-wet displacement at the shared known-water edge. Globally flattening
water cannot pass. The packer's exact 80-byte primitives are read from the same binary by Slang's
CPU target and actual Vulkan compute; no hand-authored substitute packet is used in the final run.

`packed-diagnostic` deliberately diagnoses the current broken rule. All 4,096 CPU and 4,096 GPU
cases reproduce the gap and pass the controls. CPU gaps are 0.0576868..1.54937 blocks; GPU gaps
are 0.0576873..1.54937 blocks. Maximum gap difference between backends is 0.000003576 blocks.
At strength one the GPU gap is 0.0576873..0.387344 blocks; strength four reaches 1.54937 blocks.
The GPU reports zero Vulkan/synchronization validation errors. Maximum deviation from the
independent vertical-ray displacement expectation is 0.000000715256 blocks.
Primitive packet SHA-256:
`3d7f610ef28148ada5f6e21e10b7b940a6af296199631796a574e937defb3b7b`.

`continuity-before` runs the real positive gate against the same production rule. It rejects all
4,096 CPU cases. Its failure is retained; diagnosis success is **not continuity qualification**.
The earlier hand-authored preliminary probe is local investigative evidence only and is superseded
by the Java-packed run above. These synthetic edges establish a policy defect, not full live-game
natural-shoreline or optical acceptance.

## Repeat and required repair

After `gradlew test` has exported the canonical field images, with Java 25/Vulkan SDK staged and
no other Minecraft process running:

```powershell
.\scripts\Test-WaterLayers.ps1 -Diagnose -OutputDir artifacts/fresh-layer-diagnostic
# This default gate intentionally fails on the current production rule:
.\scripts\Test-WaterLayers.ps1 -OutputDir artifacts/fresh-layer-continuity
```

`-CpuOnly` skips Vulkan; it cannot qualify GPU continuity. Fresh output directories are required.
The result explicitly distinguishes `reproduced-discontinuity` from `continuous`; only a full
non-diagnostic CPU/GPU run can set `ContinuityQualified=true`. Input and relevant source hashes
are written before a gate can fail. Raw tables, field images and logs live locally under
`artifacts/2026-10-16/water-layer-repro/` and are ignored by Git.

Repair must derive each canonical node's water layer consistently from a shared full-quad height
domain. A per-triangle maximum or affine extrapolation can reintroduce the earlier nonplanar
diagonal disagreement. Preserve all four corner heights, both diagonals, exact flat-side behavior
and canonical ownership across signed anchors; request every needed source layer. Keep the existing
constant-height fast path and 80-byte primitive layout where possible. Layer selection must handle
full-cell ties and mip quantization coherently. A repaired positive probe must pass without reducing
wave strength/detail, globally suppressing displacement, or relabeling unknown data as known.

Follow with field/root/temporal regression probes, actual strict Vulkan game reload/travel/pressure,
and mixed/near guide comparisons on varying-height and covered water before claiming the full
near/far or natural-shoreline requirement complete.

A compact candidate can preserve the common encoding: free-top tag one retains its constant source
layer and the present fast path; tag two can reuse `v2.w` for the triangle's missing fourth-corner
height. Reconstructing the same four-corner domain on both halves then permits per-canonical-node
layer selection. Constant regions may skip a tile only when that domain's layer is constant over
the skipped region. CPU field requests must cover the corresponding node-layer ranges using
integer world anchors. This is an implementation direction, not implemented acceptance; quantized
full-cell ties, nonplanar diagonals and known/unknown/covered data still need explicit tests.
