# Coincident water edges: source-layer discontinuity

The production defect reproduced on `0212c2e`/`b38dc75` is now repaired in the development path.
This does not establish that the player's entire scene is explained. Finite water stays off
by default. No PCL file was modified.

Before this repair, `RtWaterGeometry.pack` stored one source layer for an entire water quad: the integer
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

`packed-diagnostic` deliberately diagnosed the old broken rule. All 4,096 CPU and 4,096 GPU
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

## Implemented repair

The 80-byte primitive keeps its original vertex, rectangle and shoreline data. Free-top tag 1
keeps the constant source-layer fast path when all four corners select the same layer. Tag 2
reuses `v2.w` for the missing fourth-corner rest height. Both triangle halves reconstruct the
same full-quad bilinear height domain and select the water layer separately at every canonical
node. The same selection is used by intersection, current shading and previous-frame shading.
The bilinear domain selects water data; it does not replace the two original rest planes.

The CPU requests all source layers needed by each owner tile, using the domain's corner extrema
over that tile's canonical nodes. Independent per-node enumeration verifies those requests.
The shader retains the same-owner four-node gather; a larger constant-region skip is legal
only if the source domain selects the same layer throughout that region.

A shared 0.001-block source-selection tolerance makes exact full-cell ties and L0-L4 UNORM16
height quantization select the water cell below. No vertices, bounds or wave amplitudes are
snapped. Static sides, unknown/known-zero water policy, and the default-off gate are retained.

## Current numerical qualification

`artifacts/2026-10-16/water-layer-fix/full-cell/` contains 4,096 production Java-packed cases
run on both Slang CPU and Vulkan compute. Each case includes three triangles: the neighboring
flat edge and both halves of the sloped quad. The expanded cases cover nonplanar fourth corners,
both diagonals, all five scales, signed Y anchors, and positive full-cell quantization error.
The alternate production field image supplies layer 176 for the full-cell cases; previous-frame
images explicitly contain known-zero data at both tested source layers.

All cases pass on both backends. Maximum GPU edge/diagonal gap and root error are each
0.000000953674 blocks; Vulkan/synchronization validation reports zero errors. The gate also
checks preserved analytic wet-edge displacement, a high unknown corner remaining flat,
known-zero water remaining flat, current/previous heights and normals, and an independent
bilinear source-layer oracle at the internal diagonal. Flattening all waves cannot pass.

The complete Gradle check passes 214 Java tests with zero failures, errors or skips, shader
compilation, Minecraft ABI verification and packaged-mod verification. Regressions pass:
canonical field 4,096 CPU + 4,096 GPU cases (zero validation errors), patch solver 4,096 cases
plus 8,587 runtime cases, and 12,960 temporal cases. These establish packet/field/solver
correctness for the exercised inputs, not full natural-world near/far visual acceptance.

## Isolated game lifecycle

`Test-WaterCache.ps1 -Label water-layer-runtime` passes with finite water enabled, OMM off,
854x480 output/Performance RR, near radius 4, Vulkan synchronization validation and NGX clear
tracing enabled. The game completes 24 height/toggle changes, asynchronous resource reload,
Nether/Overworld travel, water-block replacement/restoration, travel to new sources, and shutdown
with pending work. Its safety fallback is exercised; stale/cancelled jobs retire without being
published. The log checker accepts zero Vulkan/synchronization errors and NGX synchronization.

Water field, water-bounds cache and native RT LOD allocations all exit at zero. Cache input,
source and pending counts also exit at zero; cache peak is 28,859,264 bytes and terrain peak
645,282,512 bytes. This is a small-window lifecycle check, not an FPS result or a driver-headroom
pressure test. Log: `water-layer-runtime.log`.

## Actual GPU interface regression

`Test-WaterGuides.ps1 -OutputDir artifacts/2026-10-16/water-layer-fix/game-guides` passes the
strict mixed/near-only comparison. Both isolated game processes use the same locked eye, zero
jitter, frozen waves and canonical current/previous field; RR is live, OMM is off, and the raw
interface images are 427x240. The clean quartz pool has a flat rest surface, so this is a
regression for existing ownership/optical guides, not the new varying-height scene acceptance.

| View / wave strength | Missing interior pixels | Max interface position error, blocks | Max physical normal-vector error |
| --- | ---: | ---: | ---: |
| Above / 1 | 0 | 0.000032369 | 0.000061378 |
| Above / 4 | 0 | 0.000233540 | 0.000234195 |
| Under / 1 | 0 | 0.001600033 | 0.000091690 |
| Under / 4 | 0 | 0.000643230 | 0.000234122 |

All four phases pass the geometry, ordinary material-guide, and static physical/normal/reflection
history gates, retaining the existing 0.002-block/interface-normal thresholds. Neither process
reports Vulkan/synchronization validation errors; field, capture, cache and terrain allocations
all exit at zero. Screenshots are illustrative; the gates read unfiltered interface and guide data.

## Normal-resolution performance sanity check

`perf-water-layer-fix` uses 1792x1120 output, actual 896x560 RR Performance rendering, runtime
Default model, near radius 16, OMM and finite water/cache enabled, one sample/four bounces.
The camera, world and waves are frozen; wave strength is 1. Validation, GPU queries, CPU
statistics, frame generation and other upscalers are off. Initial warmup is at least 90 seconds
and extends until builds/field/cache settle, then each phase settles eight seconds and measures
thirty. The repeated identical phases give:

| Phase | Frames | Mean ms | p95 ms | p99 ms |
| --- | ---: | ---: | ---: | ---: |
| 0 | 827 | 36.267 | 38.018 | 40.043 |
| 1 | 823 | 36.387 | 38.500 | 42.335 |

The 1,650 frames have a weighted mean of about 36.327 ms (27.53 FPS). The analyzer accepts all
invariants and sets `cadenceQualified=true`: 6,455 near instances, 2,006 LOD sections, near radius
16, 627,368 known water nodes, field upload 356, 2,243 ready/cache-selected sources, and 3,024
cache publications remain fixed, with no pending work or eligible misses. Native field/cache/LOD
allocations exit at zero; cache peak is 47,348,992 bytes. The new layer policy changes field
coverage relative to the earlier renderer, so this is a fixed-scene sanity measurement rather
than a controlled before/after speedup or a player-world performance acceptance.

Retained development JAR: `artifacts/2026-10-16/water-layer-fix/gysf-water-layer-fix.jar`, SHA-256
`40e8d1cfa61d23d219a46553edf833ba44e9a9aea8e6099b662d713ac7d9cf33`.
The bundled water-intersection shader is `519a540f615fff08ddc2db4a7781706b072351bd5a304d52af64e34adc589845`;
the bundled closest-hit shader is `39cc3a4c7f7e27c6d18750aca60321d7ee53146956c8c209e19f5541ef8cc3a8`.
Provider remains unchanged at `d4097ba`. Source hashes and evidence are retained beside the JAR.

## Driver-headroom pressure with the water cache

`water-layer-pressure` completes in the isolated game at 854x480, near radius 32, OMM off,
finite water and its 64 MiB cache enabled, with Vulkan synchronization validation and NGX clear
tracing. After a 180-second warmup it ramps actual unused device buffers to 1,792 MiB and releases
them 45 seconds after pressure begins. The near window contracts 32 -> 24 and returns through
26/28/30 to 32 after release. Exact instances change 25,350 -> 17,551 -> 25,517. Completion
requires at least sixty seconds after release and ten seconds without near/LOD builds at the
recovered radius; 289 published-ownership checks pass. The 1,738-section LOD selection remains
resident through the pressure/recovery record; this uses the wide near window, unlike the
2,006-section selection of the radius-16 cadence run.

The cache reaches its 67,108,864-byte limit and records 10,294 successful publications
throughout the session. Water-field, cache, cache input/source/pending and native terrain exit
counts are all zero; native LOD peak is 669,087,472 bytes with no allocation deferrals. The log
checker accepts zero Vulkan/synchronization errors and confirms live RR/NGX clear synchronization.
This qualifies the exercised pressure/recovery lifecycle, not moving-camera optics or frame rate.

The first post-run checker invocation rejected the log because pressure-only mode emits
`GYSF_NEAR_PRESSURE: PASS`, without a generic smoke/performance PASS. The checker now requires
an explicit `-RequireNearPressure` option, a unique complete contraction/recovery/retirement and
ownership record, and all existing error/shutdown gates. Five mutated logs (missing completion,
unrecovered radius, validation error, live terrain and retained cache input) are rejected; the
original lifecycle log still passes. `Test-Performance.ps1 -NearMemoryPressure` invokes this
gate automatically. The game binary was unchanged; no second pressure run was needed.

```powershell
.\scripts\Test-Performance.ps1 -RenderDistance 32 -Width 854 -Height 480 -InitialSeconds 180 `
    -Presets '0' -NearMemoryPressure -PressureOnly -ValidateVulkan -FiniteWater -Label fresh-water-pressure
.\scripts\Assert-VulkanSmokeLog.ps1 -Path fresh-water-pressure.log -RequireNearPressure `
    -RequireWaterBoundsCache -RequireNgxClearSync
```

The explicit NGX check additionally needs `NGXSHIM_SYNC_TRACE=1` during the game run; the
recorded run also set `VK_KHRONOS_VALIDATION_VALIDATE_SYNC=true`.

## Repeat and remaining acceptance

The follow-up [actual provider source and edge validation](WATER-SOURCES-2026-10-16.md) now
passes with real L0-L4 packets and canonical data on CPU/Vulkan compute. It remains separate
from the full-game varying-height optics and active-ownership acceptance below.

After `gradlew test` has exported the canonical field images, with Java 25/Vulkan SDK staged and
no other Minecraft process running:

```powershell
.\scripts\Test-WaterLayers.ps1 -OutputDir artifacts/fresh-layer-continuity
```

`-CpuOnly` skips Vulkan; it cannot qualify GPU continuity. Fresh output directories are required.
The historical `-Diagnose` mode is an inverse failure predicate and must not be used to qualify
this repair. The result distinguishes `reproduced-discontinuity` from `continuous`; only a full
non-diagnostic CPU/GPU run can set `ContinuityQualified=true`. Input and relevant source hashes
are written before a gate can fail. Raw tables, field images and logs live locally under
`artifacts/2026-10-16/water-layer-repro/` (old reproduction) and
`artifacts/2026-10-16/water-layer-fix/` (repair), ignored by Git.

Mixed/near guide comparisons on varying-height and covered water, and moving-camera natural-world
checks, remain necessary before claiming the full near/far or
natural-shoreline requirement complete. Coincident rest-edge displacement continuity
also does not prove that the underlying coarse and near rest meshes already coincide everywhere.
