# Current-build performance investigation

This continues the water integration on `a748a40`; it does not qualify the older installed PCL
package. Experiments use the isolated `run-smoke` world and never modify the player's instance.

## Measurement contract

The performance harness now varies NGX quality separately from the RR model preset. It records the
actual trace/output dimensions and actual reconstruction filter from the compositor, not just the
requested options. SPP, bounce cap, finite-water mode, actual OMM support, near/LOD build activity,
geometry versions, and canonical water-field generation are retained alongside every measured frame.
Changing geometry or field state rejects a measured phase. The analysis also rejects changes between
phases; a smaller resident scene cannot masquerade as a model speedup.

RR-off explicitly selects native-resolution SVGF, with NRD, FSR, XeSS and frame generation off.
This is a useful renderer comparison, not a claim of equal reconstruction quality. Wave speed is zero,
the player's feet are fixed at `(0.5,140,0.5)` (eye approximately y=141.62), yaw 0, pitch 20, and
the server freezes during warmup.
Render-thread CPU CSV logging and Vulkan validation are off for production-cadence runs.

`-GpuTimings` adds sampled GPU intervals for preparation, camera medium, primary trace, indirect trace,
reconstruction, exposure, display mapping and copy. Only every eighth frame is instrumented. A bounded
32-slot query pool waits for the exact graphics completion **without issuing a host wait**, then reads
that slot's results; full rings skip samples rather than stall. This completion check also excludes
stale query availability from a preceding slot generation. The pool is destroyed during device-idle
compositor teardown. Ordinary rendering creates no timing pool or output file.

The query lifetime follows the Vulkan specifications for
[query result reuse](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetQueryPoolResults.html),
[timestamps](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdWriteTimestamp.html) and
[GPU query resets](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdResetQueryPool.html).
These GPU intervals include pipeline dependencies and bubbles between boundaries; they are not
hardware shader counters or an attribution of every part of the full-frame wall interval.

`Analyze-Performance.py --gpu ...` pairs samples by exact RT frame serial and checks dimensions,
filter and model state. Its `comparisonAccepted` means the scene comparison passes its invariants.
The separate `cadenceQualified` flag additionally requires run metadata and no validation, GPU timing
or CPU CSV instrumentation. Eight retained negative/positive fixtures check filter mismatch, native
resolution, scene version, water generation, GPU pairing, incomplete phases and diagnostic status.

## Timing lifetime validation

`perf-current-timing-validation` ran on the RTX 5060 Laptop GPU / NVIDIA 616.92 with validation active,
OMM off, finite water on, near radius 4, output 854x480 and actual trace 427x240. It completed 644
measured frames and 754 GPU timing samples over the entire run, with zero skipped slots and
`livePools=0` at exit. The Vulkan log checker passes; native LOD and water-field live bytes return to
zero. This is a timing/resource validation run, **not qualified normal-gameplay FPS**.

The 80 exact samples paired to measured frames average 15.115 ms inside the timed compositor:
primary trace 3.919 ms, indirect trace 10.113 ms, reconstruction 0.841 ms, preparation 0.168 ms;
the remaining stages total about 0.074 ms. This points toward ray-tracing cost in this small control,
but cannot establish the dominant cost at a useful output resolution or with OMM enabled.

Local evidence is under `artifacts/2026-10-16/performance-current/` and
`run-smoke/performance-smoke/perf-current-timing-validation/`. The generic water-field smoke gate
requires a dedicated `GYSF_WATER_FIELD: PASS` fixture marker; that marker is intentionally absent from
the performance harness. Its rejection with `-RequireWaterField` is not reported as a field-fixture
pass. The ordinary validation/error and zero-native-shutdown check passes, and the actual field
shutdown reports zero separately.

## OMM-on legacy-water model diagnostic

`perf-current-flat-diagnostic` completed at 1792x1120 output / 896x560 trace, near radius 16,
OMM on and finite water off, one sample and four bounces. All 3,109 measured frames retained
6,455 exact near instances and 2,006 LOD sections with constant geometry versions and no build work.
This is the legacy displaced-shading water route, not a wave-free image. GPU queries sample every
eighth frame, so its full-frame cadence is **instrumented diagnostic evidence**, not qualified
production-cadence acceptance or a cross-route speedup.

| Requested RR model | Wall mean ms | Paired GPU mean ms | Indirect trace ms | RR reconstruction ms |
| --- | ---: | ---: | ---: | ---: |
| Runtime default, first | 19.241 | 19.096 | 14.169 | 2.906 |
| Explicit F | 19.319 | 19.136 | 14.208 | 2.926 |
| Runtime default, repeat | 19.270 | 19.164 | 14.246 | 2.923 |

The requested F model does not show a material additional cost over runtime default in this control.
This does not identify which internal model Default selected or prove performance in the player's
world. Indirect tracing occupies about three quarters of the timed compositor. The run completed
1,370 GPU samples, no skipped slots, zero live query pools/native LOD bytes on exit, and no Vulkan
error report in the log. Field residency is inactive for this route. The analysis accepts its scene
invariants and explicitly sets `cadenceQualified=false` because GPU timing is enabled.

## Rejected short native-resolution matrix

`perf-current-native-matrix` requested 1792x1120 output, near radius 16, OMM on, finite water on,
one sample and four bounces. Its first two phases retained 6,455 near instances, 2,006 LOD sections,
614,560 known water nodes, field upload generation 353, and zero pending field requests.
Runtime-default RR measured 65.416 ms at 896x560 and 108.247 ms at 1195x747 in those partial phases.
The next native-resolution phase collected only 53 frames inside the requested 12-second window,
so the minimum-60-frame gate rejected the run. It has **no complete matrix acceptance or qualified
shutdown**. Its partial observations are retained to motivate the investigation; they do not repair
the user's reported performance or prove any speedup.

Inspection found that fully damped/unknown water patches still evaluated the complete wave spectrum
and its bounds, only to multiply all results by zero. The candidate fast path evaluates the patch
weight first, returning exact zero height/gradient when both weight and gradient are zero; an
all-zero patch also returns the same flat bounds directly. Mixed shore transitions retain the full
calculation. This changes no wave detail, bounce cap, brightness or water coverage.

The experimental candidate passes the 4,096-root patch suite, 8,587 runtime cases, 12,960 temporal cases, and
4,096 CPU plus 4,096 actual GPU field cases. GPU validation reports zero errors, maximum root error
2.86102e-5 blocks and maximum work 84. The full build/check passes 207 Java tests, shader/ABI and JAR
checks. These checks establish bounded numerical correctness, not a speed benefit.

## Completed candidate run and rollback

`perf-current-finite-fastpath` completed at the same output/trace size, near radius and OMM settings
as the legacy-water diagnostic, with the candidate and finite-water path on. All 910 measured frames
kept 6,455 near instances, 2,006 LOD sections, 614,560 known water nodes and zero pending field requests.
Geometry versions and field generation stayed constant across all phases. Validation, CPU CSV and GPU
timestamps were off, and `cadenceQualified=true` for **this experimental candidate**.

| Requested RR model | Frames | Mean ms | FPS | p95 ms |
| --- | ---: | ---: | ---: | ---: |
| Runtime default, first | 299 | 66.848 | 14.96 | 72.698 |
| Explicit F | 298 | 66.763 | 14.98 | 72.085 |
| Runtime default, repeat | 313 | 63.831 | 15.67 | 72.783 |

The earlier unmodified-water partial observation (65.416 ms) lies between the two candidate Default
observations. There is no demonstrated useful frame-time improvement, and Default/F differences are
smaller than the repeated Default drift. The candidate shader changes have therefore been **rolled
back**. `water_patch.slang` is restored to its pre-experiment source. The final source retains only the
opt-in measurement tools and stricter evidence checks; it does not ship a claimed water speedup.

The completed candidate has zero native LOD/water-field live bytes at shutdown and no Vulkan error
report in its log. Its rejected patch and binary are retained under the local evidence directory:
`rejected-flat-fastpath.patch` and `gysf-0.7.0-dev+mc26.3-rejected-flat-fastpath.jar` (SHA-256
`10167CF22306ED06E678D55BE84E81C5D898D7EABC5E660C910D7BD03E1BDBF6`). They are experimental
evidence, not the final delivery candidate. Neither was installed into PCL.

The approximately 19-ms legacy-water diagnostic and 64–67-ms finite-water candidate show that the
experimental physical-water route carries a large cost in this fixed scene. They render different
water geometry and use different instrumentation, so this is not a quality-equivalent speedup ratio
or a clean attribution to one solver function. It identifies the next investigation target.

## Reproduction and next work

With Java 25, Vulkan SDK 1.4.341.1, Slang 2026.18 and DLSS SDK 310.9.1 configured:

```powershell
.\scripts\Test-Performance.ps1 -Label fresh-flat -RenderDistance 16 -Width 1792 -Height 1120 `
  -InitialSeconds 120 -SettleSeconds 8 -MeasureSeconds 20 -Presets '0,6,0' -Qualities '0' -EnableOmm -GpuTimings
python scripts/Analyze-Performance.py run-smoke/performance-smoke/fresh-flat/frames.csv `
  --gpu run-smoke/performance-smoke/fresh-flat/gpu.csv --output fresh-flat.json
```

Adding `-FiniteWater` selects the experimental physical-water route. Omitting `-GpuTimings` provides
uninstrumented cadence, while preserving actual-frame dimensions/filter and scene checks. The
rejected candidate's special shader fast path is deliberately absent from current source, so merely
repeating its label/flags does not reproduce that abandoned candidate. Use fresh labels for every run.
The log checker has 18 fixtures, including required timing samples, missing initialization, and live
query-pool rejection. Its `-RequireGpuTimings` mode accepts both completed timing runs above.

Next, measure normal-resolution finite-water GPU stages and solver work, then test reuse of
ray-invariant wave bounds across canonical cells and elimination of repeated intersection work.
Require matched field/geometry coverage and repeated before/after cadence before retaining an
optimization. The full DLSS quality/native/SVGF matrix still needs a sufficiently long measurement
window; the rejected 12-second run above must not fill that gap.

Complex shorelines, animated travel and player-world visual/performance acceptance remain open.
Finite water stays default off. The player's PCL package/configuration is unchanged. No global
brightness change, bounce reduction or automatic RR model selection is justified by these runs.
