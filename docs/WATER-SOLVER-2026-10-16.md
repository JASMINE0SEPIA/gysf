# Water solver: representable roots and controlled shader comparisons

This follows the measurement-only checkpoint `806a752` in
[PERFORMANCE-2026-10-16.md](PERFORMANCE-2026-10-16.md). The retained changes terminate
ineffective bisection and preserve analytically clipped flat-water hits. Finite water remains
experimental and default off. All game runs use the isolated `run-smoke` instance; no player
instance, configuration, world or installed mod was changed.

## Ineffective bisection

After a sign change, the first-root solver previously evaluated all 16 bisection steps even if
the floating-point midpoint had already rounded to an endpoint. Subsequent iterations could no
longer refine the returned midpoint. Stop when `mid == a || mid == b`; retain the same midpoint,
wave spectrum, intersection tolerance and physical surface.

The 4,096-ray host table is bit-identical to the original shader, including hit/miss and distance
words (SHA-256 `eeb93a7fc4e2911baa085323ca7baacb1a645f39708c059cc44eb4463d129d47`). Root
equation evaluations fall from 11,322 to 9,954 (12.08%), maximum work from 26 to 21. The suite
retains 1,346 hits, 2,750 misses, no reference disagreements or exhaustion, and passes 8,587
runtime checks. The field GPU probe retains maximum root error 2.86102e-5 blocks and reduces
maximum work from 84 to 76. These are work/correctness results, not an FPS percentage.

`perf-stagnation-abba` switches A-B-B-A inside one game process, with one immutable scene and
field snapshot. Output is 1792x1120, actual trace 896x560, near radius 16, OMM and finite water
on, runtime-default DLSS RR, one sample and four bounces. Validation, GPU timestamps and CPU
statistics are off. Each phase settles for 8 seconds, then measures for 20 seconds.

| Phase | Shader | Frames | Mean ms | p95 ms |
| --- | --- | ---: | ---: | ---: |
| 0 | Original A | 402 | 49.556 | 51.764 |
| 1 | Bisection B | 424 | 47.023 | 49.525 |
| 2 | Bisection B | 426 | 46.740 | 48.858 |
| 3 | Original A | 403 | 49.500 | 52.088 |

Frame-weighted means are about 49.528 ms and 46.881 ms: **5.34% lower frame time in this fixed
scene**. Both repeated candidates beat both baselines. The analyzer accepts geometry, versions,
field, render settings and actual shader order, with `cadenceQualified=true`. All phases keep
6,455 exact near instances, 2,006 LOD sections, 614,560 known field nodes, upload generation 305
and zero pending work. This is not player-world visual acceptance or a promise for other scenes.

Original intersection module SHA-256:
`1f81083e81e11200eeb2825ba5850ec29f0694a32672cfed5c88e89f5127259b`.
Bisection-only module:
`ab40a1d7b70322355cd01c701c76dbf393fdbf3d73e267c1b43f46a6f7efda76`.
The archived original JAR from `806a752` hashes to
`02b5738b7873fa2e43de2280b8f86629bb00370786c40ae982d602132be933f2`.

## Flat-water misses on long rays

Unknown canonical cells deliberately have zero displacement. Their height envelope clips a ray
to the analytic rest-plane crossing. The old solver then evaluated `offset + rate * t` at this
rounded root. At distances around 10,000-16,000 blocks, that residual can exceed the fixed 1e-6
tolerance despite a valid plane crossing. Since the clipped interval has already collapsed to
one value, the solver immediately reports a miss.

A new independent analytic-plane fixture reproduces this: the initial 32 distant rays have one
CPU miss and **22 GPU misses**, with zero Vulkan validation errors. Accepting the already clipped
root when both height bounds are exactly zero removes all these misses on both processors.
This applies to ordinary patches and canonical cells. Ray interval and triangle clipping still
run first, including rejection of parallel flat rays; there is no enlarged hit tolerance.

The retained fixture expands coverage to 96 rays over three distance bands (roughly 110-16,500
blocks), above/below, positive/negative horizontal directions, both triangle halves, inclusive
ray-limit endpoints, and rejection outside either
ray limit or the primitive half. It adds these checks to the existing field/root fixtures rather
than replacing their original coverage. The expanded 4,096-case CPU and GPU runs pass, retaining
the existing 2.86102e-5 maximum near-root GPU error and zero validation errors.

This is a reproduced numerical hole in the experimental water route. It is not proof that every
reported near/far seam has the same cause. Known non-flat cells, complicated coarse source
elevations and motion/history behavior still need separate coverage.

## Combined candidate in the game

`perf-flat-root-abba` repeats the same uninstrumented normal-resolution protocol for both
retained fixes together. All 1,601 measured frames keep 6,455 near instances, 2,006 LOD sections,
614,560 known nodes, upload generation 303 and zero pending work, with unchanged scene versions.
The analyzer accepts the measured shader order and all scene/render invariants.

| Phase | Shader | Frames | Mean ms | p95 ms |
| --- | --- | ---: | ---: | ---: |
| 0 | Original A | 383 | 52.070 | 56.156 |
| 1 | Combined B | 428 | 46.658 | 48.819 |
| 2 | Combined B | 414 | 48.204 | 65.603 |
| 3 | Original A | 376 | 52.988 | 56.044 |

Frame-weighted means are 52.525 and 47.418 ms (19.04 and 21.09 FPS), **9.72% lower mean frame
time in this scene**. The combined shader retains a lower mean than the original;
this does not isolate the correctness fix's speed contribution from the earlier bisection change. The
second B phase has a worse p95 than either A: long-frame behavior remains unresolved. No frames
were removed to obtain these results. Different-process means from the bisection-only run must
not be used to claim an additional percentage speedup.

The combined module hashes to
`a07be99b91741004107e794719c93b3489f0f6afff786d82e4af87079595d837`; the archived shipping JAR
is `artifacts/2026-10-16/water-bounds/gysf-water-root-final.jar`, SHA-256
`8824a613fabda2c7a7cdf39d9d66b1c9615a748fd52a196f516037104dc15344`.
The log passes ordinary smoke acceptance and reports zero alternate pipelines/native LOD/field
bytes at shutdown. Validation layers were off for the cadence comparison; GPU field validation
is a separate passing check. The final three-distance-band field probe passes on CPU and GPU.

## Comparison mechanism and rejected experiments

Before these candidates, `perf-bounds-baseline-gpu` completed the normal-resolution finite-water
diagnostic with the archived `806a752` intersection module and OMM on. Its 403 measured frames
average 49.490 ms wall cadence. The 50 exact frame-paired GPU samples average 49.577 ms:
primary tracing 13.906 ms, indirect tracing 32.382 ms, RR reconstruction 2.715 ms, preparation
0.423 ms; all remaining stages sum to about 0.152 ms. There are 788 total sampled GPU frames,
no skipped slots, and zero query pools/native LOD/field bytes at shutdown. This is an
instrumented diagnostic, not production cadence. The runtime metadata says dirty source because
a candidate was being prepared after launch; the actual baseline binary was independently
archived and its module hash verified. Its large variation from earlier separate-process
measurements is why the subsequent comparisons use immutable pipelines in one process.

The smoke source set creates two immutable RT pipelines with matching descriptor/push-constant
definitions. It switches the executable and its matching shader-binding table after an idle
boundary; the original descriptors, material epoch, acceleration structures and geometry remain
in place. Only `world_water.rint.spv` differs. Recorded frames verify actual executable/SBT
selection, and the analyzer checks observed module hashes against the requested order and requires
matching recorded render settings. Negative fixtures reject a mismatched internal width and a
candidate phase containing the baseline module; the unchanged positive run still passes.

Vulkan permits descriptor reuse across identically defined compatible layouts; see the official
[pipeline layout compatibility rules](https://docs.vulkan.org/spec/latest/chapters/descriptorsets.html#descriptorsets-compatibility).
The small `perf-bounds-swap-validation` A-B-B-A run exercised this mechanism with strict Vulkan
and synchronization validation: 3,540 measured frames, 1,392 GPU timestamp samples, no validation
error report, and zero alternate pipelines/query pools/native LOD/field bytes at shutdown.
Its cached-bounds candidate showed no material gain and was discarded. Its timing is diagnostic,
not production cadence. Comparison classes/mixins are absent from the shipping JAR.

Two other investigations were discarded: a safeguarded monotone Newton candidate saved only
about 2.3% of host root evaluations without game timing evidence; a copied GPU-local field
allocation showed no observed benefit in the small validation control. Neither optimization nor
its production API changes remain. Full SPIR-V hashes can differ solely from embedded source
line endings; the field-placement run is not accepted as a clean shader-identical memory timing
comparison. The analyzer's identity gate was not relaxed.

## Reproduction and evidence

Local logs, archived modules/JARs and raw probe results live under
`artifacts/2026-10-16/water-bounds/`. Game CSVs and run metadata live under
`run-smoke/performance-smoke/<label>/`; these directories are intentionally ignored by Git.
Use fresh labels and output directories.

```powershell
.\scripts\Test-WaterPatch.ps1 -RayTable artifacts/fresh/roots.csv
.\scripts\Test-WaterField.ps1 -OutputDir artifacts/fresh/field
.\scripts\Test-WaterTemporal.ps1
.\scripts\Test-Performance.ps1 -Label fresh-abba -RenderDistance 16 -Width 1792 -Height 1120 `
  -InitialSeconds 120 -SettleSeconds 8 -MeasureSeconds 20 -Presets '0,0,0,0' -Qualities '0' `
  -FiniteWater -EnableOmm -WaterShaderBaseline <archived-original-water.spv> -WaterShaderSequence '0,1,1,0'
python scripts/Analyze-Performance.py run-smoke/performance-smoke/fresh-abba/frames.csv `
  --output artifacts/fresh/abba.json
```

The archived original module can be extracted from `caustica/rt/world_water.rint.spv` inside the
baseline JAR. The bisection-only result above applies to its recorded module, not to any future
candidate sharing a run label. GPU probes must run without Minecraft. Full Gradle `check` (207 Java tests) and
smoke Java compilation pass; the checker retains its 18 log fixtures. The final combined shader
also passes 4,096 patch rays, 8,587 runtime cases and 12,960 temporal cases.
