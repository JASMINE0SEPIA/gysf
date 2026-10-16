# Current water renderer: DLSS quality and model cost

This measures production source `0212c2e` with the bounded water cache, paired with provider
`d4097ba`. It does not describe the older installed PCL package. The player's instance was read
only; every game test uses `run-smoke`. Finite water remains experimental/default off.

## Repeated quality matrix

`perf-cache-quality-matrix` runs on RTX 5060 Laptop GPU / NVIDIA 616.92 at 1792x1120 output,
near radius 16, OMM on, finite water and its cache on, one sample and four bounces. Runtime-default
RR model is preset zero. Frame generation, other upscalers and NRD are off; RR-off selects native
SVGF. The fixed camera, frozen server and frozen waves follow the existing measurement contract.
Every phase settles eight seconds and measures thirty. Validation, GPU queries and CPU statistics
are off. The eleven-phase order is Ultra Performance, Performance, Balanced, Quality, native RR,
native SVGF, native RR, Quality, Balanced, Performance, Ultra Performance.

| Filter / quality | Actual trace size | Frames | Weighted mean ms | FPS | Repeated mean range, ms |
| --- | --- | ---: | ---: | ---: | --- |
| RR Ultra Performance (3) | 597x373 | 3,137 | 19.116 | 52.31 | 19.107..19.126 |
| RR Performance (0) | 896x560 | 1,622 | 36.950 | 27.06 | 36.927..36.974 |
| RR Balanced (1) | 1039x650 | 1,267 | 47.301 | 21.14 | 47.257..47.346 |
| RR Quality (2) | 1195x747 | 1,024 | 58.504 | 17.09 | 58.419..58.589 |
| Native RR / DLAA (5) | 1792x1120 | 506 | 118.177 | 8.46 | 117.815..118.538 |
| Native SVGF | 1792x1120 | 265 | 112.754 | 8.87 | Single central control |

All 7,821 measured frames retain 6,455 near instances, 2,006 LOD sections, 614,560 known water
nodes, field upload 265, 2,241 cache-ready sources and 2,681 cache publications. There is no
pending geometry/cache work, eligible cache miss or scene/version change. The analyzer accepts
its invariants and sets `cadenceQualified=true`. Native cache, field and terrain allocations exit
at zero; cache peak is 47,342,592 bytes. These different internal resolutions and reconstruction
filters do not have equal visual quality. The table is a cost matrix, not a quality-equivalent
speedup or a recommendation to reduce settings automatically.

## GPU stage and F-model diagnostic

`perf-cache-model-stages` uses the same display size, near radius, OMM/finite-water settings and
frozen scene. It measures twenty seconds after eight-second settles. Seven phases compare
Default/Performance, F/Performance, Default/native, SVGF/native, Default/native, F/Performance,
Default/Performance. GPU timestamps sample every eighth frame and are joined to the exact RT
frame serial and actual filter/dimensions. Therefore this is **instrumented diagnostic timing**,
with `cadenceQualified=false`, separate from the above cadence result.

| Phase | Requested model / quality | Exact GPU pairs | Primary ms | Indirect ms | Reconstruction ms | Total timed GPU ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 0 | Default / Performance | 71 | 8.617 | 22.830 | 2.722 | 34.749 |
| 1 | F / Performance | 72 | 8.696 | 22.764 | 2.734 | 34.776 |
| 2 | Default / native | 22 | 26.001 | 77.066 | 7.850 | 111.502 |
| 3 | SVGF / native | 19 | 28.500 | 90.834 | 7.876 | 127.777 |
| 4 | Default / native | 22 | 26.499 | 78.013 | 7.847 | 112.948 |
| 5 | F / Performance | 71 | 8.652 | 22.818 | 2.726 | 34.786 |
| 6 | Default / Performance | 71 | 8.637 | 22.778 | 2.722 | 34.726 |

Primary plus indirect tracing account for approximately 90..93% of the sampled compositor
intervals. F and Default reconstruction cost about 2.72..2.73 ms at Performance in this control;
neither has a demonstrated useful cost advantage. The native SVGF relative wall/GPU rate differs
from the uninstrumented run. Sparse query samples, temporal path/filter differences and changing
device clocks limit interpreting a small native-filter delta; do not combine the two processes
into a precise claim that disabling RR gains or loses a fixed percentage. Both diagnostics point
to ray work as the main target in this scene, rather than a forced RR-model change.

The stage run passes scene, field, cache and render invariants; all 1,228 query samples retire with
zero skipped slots and zero live pools. Cache/field/terrain exit bytes are zero. These are sampled
pipeline intervals, including dependencies between boundaries, not vendor hardware shader counters.

## Relation to the player's report

The latest available player log/config inspected on October 16 was last written October 15. It
records actual 896x560 RR rendering to 1792x1120 and a user switch from model zero to F, with
frame generation/other upscalers off. Thus the reported slow session already used Performance.
Its later driver usage rises from 6,960 to 7,497 MiB against roughly 6,923..6,999 MiB budgets,
with a temporary 128-block distant limit. This is strong evidence of memory pressure in that old
session; it is not evidence of a current-version leak or an isolated RR cost measurement.

The earlier [memory investigation](PERFORMANCE-2026-10-15.md) independently diagnoses retained
VMA pool storage, adds allocator-specific headroom credit and settled-build recovery, and qualifies
pressure/retirement/refill. Those newer changes were not installed by this measurement work.
Moving/full-window tests with the new water cache and player-world acceptance remain separate.

## Reproduction and evidence

With the staged Java 25, Vulkan SDK, Slang 2026.18 and DLSS SDK 310.9.1, no other Minecraft:

```powershell
.\scripts\Test-Performance.ps1 -Label fresh-quality -RenderDistance 16 -Width 1792 -Height 1120 `
  -InitialSeconds 90 -SettleSeconds 8 -MeasureSeconds 30 -FiniteWater -EnableOmm `
  -Presets '0,0,0,0,0,-1,0,0,0,0,0' -Qualities '3,0,1,2,5,5,5,2,1,0,3'
.\scripts\Test-Performance.ps1 -Label fresh-stages -RenderDistance 16 -Width 1792 -Height 1120 `
  -InitialSeconds 90 -SettleSeconds 8 -MeasureSeconds 20 -FiniteWater -EnableOmm -GpuTimings `
  -Presets '0,6,0,-1,0,6,0' -Qualities '0,0,5,5,5,0,0'
```

Run `Analyze-Performance.py frames.csv --output report.json`; add `--gpu gpu.csv` only for the
stage diagnostic. Raw CSVs, metadata and captures remain under their fresh
`run-smoke/performance-smoke/` labels. Summaries/logs are archived locally under
`artifacts/2026-10-16/dlss-quality-current/`. The tested JAR remains SHA-256
`abb8481087a4abf71ce6217716d64a12d5bbb245782f546cba171e2f09e3741e` and the `0212c2e` cloud CI
run 38937170110 is confirmed successful. CI does not run or qualify these GPU scenes.

The current shoreline source-layer defect is reproduced separately in
[WATER-LAYERS-2026-10-16.md](WATER-LAYERS-2026-10-16.md). Repairing continuity and validating
natural/coarse/covered topology remain required before default finite-water activation.
