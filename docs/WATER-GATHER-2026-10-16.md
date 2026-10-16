# Shared-owner water field gather

Follow-up to [the root fixes](WATER-SOLVER-2026-10-16.md), using `721e623` as baseline.
The four corners of an interior unit cell share one 16x16 field owner. Look up that owner once
and gather its four adjacent words directly; retain the independent-owner lookup on tile edge
strips. Uniform owners return one replicated weight and absent owners return zero. Field layout,
sampling/interpolation, wave detail and physical geometry remain unchanged.

The 4,096-case CPU/GPU field suite passes with zero Vulkan validation errors. All decoded FP32
GPU readback values match the baseline bit for bit, including work counts and errors. This covers
mixed/unknown nodes, signed origins, tile boundaries, world-border anchors, fine/coarse roots and
the preceding flat-water distance regressions. The first prototype's transposed owner key was
caught by the host gate and fixed before the GPU/game comparisons below.

The shared helper is used by both the intersection and closest-hit stages. The smoke comparator
can now load an optional archived `world.rchit.spv` alongside `world_water.rint.spv`, select both
through the same pipeline/SBT, and record both actual hashes per measured frame. The analyzer
checks stable hit-stage identities and the baseline, as well as the existing scene/render/order
gates. Three negative fixtures reject a wrong hit-stage baseline, missing hit identity and a
candidate that changes between phases. The shipping JAR contains no smoke classes or mixins.

## Game evidence

The Vulkan-validation control (`perf-cell-gather-validation`) completes 3,690 measured frames at
854x480 output / 427x240 trace, near radius 4, OMM off, finite water on. All phases retain 543 near
instances, 1,738 LOD sections and 562,783 known field nodes. A-B-B-A mean times are 11.005,
10.718, 10.693 and 10.913 ms. Validation reports no errors; alternate pipelines, native LOD and
field allocations are zero at shutdown. This is a validation diagnostic, not qualified cadence.
Synchronization validation was not explicitly enabled for this control.

`perf-cell-gather-abba` measures normal cadence with validation, timestamps and CPU CSV off:
1792x1120 output / 896x560 trace, near radius 16, OMM and finite water on, runtime-default DLSS RR,
one sample and four bounces. Each phase settles for 8 seconds and measures for 20 seconds.

| Phase | Shader pair | Frames | Mean ms | p95 ms |
| --- | --- | ---: | ---: | ---: |
| 0 | Baseline A | 412 | 48.361 | 53.268 |
| 1 | Gather B | 424 | 47.142 | 53.745 |
| 2 | Gather B | 430 | 46.410 | 48.980 |
| 3 | Baseline A | 409 | 48.774 | 53.653 |

All 1,675 frames keep 6,455 near instances, 2,006 LOD sections, 614,560 known nodes, upload
generation 313 and zero pending work, with identical scene versions and recorded settings.
The analyzer accepts both shader identities and sets `cadenceQualified=true`. Frame-weighted
means are **48.566 to 46.773 ms (3.69% lower in this fixed scene)**. Both candidates beat both
baselines on mean time. Tail behavior is still variable; this is not a player-world FPS promise.
Do not add this percentage to earlier separate-process results. Both stages switch together,
so the result does not attribute the gain to either stage alone.

Baseline intersection / hit hashes:
`a07be99b91741004107e794719c93b3489f0f6afff786d82e4af87079595d837` /
`2280f834652e5a5a0c1770168d79f68e26e06f7c664b4d072b407ed8429ba8c9`.
Candidate intersection / hit hashes:
`e1e14f96930ae6e067dbb651378740826ba2f2b83a0a1f6dda00004684a47db3` /
`6401d6528c1c3dee688bc112fab1fd7377ee8d826a3542f4f748474469ab674a`.

Local raw evidence is under `artifacts/2026-10-16/water-cell-gather/` and the matching
`run-smoke/performance-smoke/` labels. The archived candidate `gysf-cell-gather.jar` hashes to
`488a963a866d4ab4d392f0371ccd067fe9bd9645f4e8146130c6cdcce6625fa6`.
Full Gradle `check` and smoke Java compilation pass (207 Java tests). The `721e623` baseline's
cloud CI also completed successfully, separately from local GPU/render acceptance.

Reproduce the existing root document's comparison command with both baseline modules:

```powershell
-WaterShaderBaseline <baseline-world_water.rint.spv> `
-WaterHitShaderBaseline <baseline-world.rchit.spv> -WaterShaderSequence '0,1,1,0'
```

Use fresh run labels. Both modules can be extracted from their named entries under `caustica/rt/`
in the baseline JAR. Finite water remains default off. No PCL package, settings or save was changed.
