# Runtime water bounds cache

This implements the measured opportunity in [WATER-BOUNDS-2026-10-16.md](WATER-BOUNDS-2026-10-16.md).
The finite-water development gate remains **off by default**. Inside that gate the optional cache
is enabled by default; `-Dgysf.rt.waterBoundsCache=false` restores original broad water BLAS use.
No PCL installation, player settings or saves were changed.

## Bounds and fallback

Every source water BLAS still covers the full 2.2-block maximum displacement. Its original
primitive attributes, material/UV tables and ownership remain authoritative. An immutable CPU
snapshot retains only original bounds plus each free top's maximum rest height. Derived builds
change only those tops' maximum Y; sides/bottoms are unchanged. Quarter-strength bins round
upward. The shader's actual strength, speed, detail, shoreline field and bounce cap are untouched.

The compositor snapshots strength and the wave toggle once, before reserving the frame's graphics
use, and passes that same strength/toggle to the shader. Only a completed cache with sufficient
height can replace a source. A larger request immediately selects the original broad BLAS while
the new build is pending. Disabled waves can use a zero-displacement bound. Nonfinite settings
conservatively select the maximum envelope; no invalid height is submitted to a native build.

Each source owns one immutable cached BLAS, not an in-place refit. The source destruction callback
closes its owner, and publication checks the owner object as well as source address, requested
height and invalidation epoch. A recycled native address cannot accept another owner's result.

## Budget, submission and destruction

GPU buffers use a separate accountant, with a default **64 MiB** quota covering AS backing,
build scratch, staging and resources awaiting actual retirement. VMA allocation sizes, including
alignment, replace requested sizes in the accounting. CPU retained input payload has its own 64 MiB
quota, reserved before cloning. The overrides are `gysf.rt.waterBoundsCacheMiB` and
`gysf.rt.waterBoundsInputMiB` (0 disables, maximum 256). Optional failures defer caching and use
the broad original; cache admission never consumes the terrain's native quota.

At most four builds start per frame and eight are queued/in flight at once. Work uses the existing
GPU executor and shared-queue-family input buffers. Completed builds are published before
`beginGraphicsUse`, attaching their timeline dependency to the graphics submission. Normal cache
operation does not wait for device idle. Heap pressure evicts optional entries and backs off.

Actual TLAS selection marks the cached object's last graphics use, including the frame's later
overlay references. Replacements retire only after that use completes. Source destruction can
itself occur inside deferred destruction; a separate retirement queue avoids recursively changing
the executor's destroy list. Failed native waits quarantine unpublished objects until executor
shutdown has stopped submissions and made the device idle. Device-loss recovery is not qualified.
Shutdown rejects new work, closes owners, joins the executor, drains remaining objects and asserts
zero native bytes, CPU inputs, source owners and pending work.

## Validation

Full Gradle `check` and smoke compilation pass **210 Java tests**, shader/ABI and JAR checks.
New CPU cases cover 40,001 slider values and adjacent quarter boundaries, invalid values, sloped
tops at signed float scales through 65,536, original/input immutability and unchanged side bounds.
The log checker passes 25 fixtures, including leaked native/input bytes, sources and pending jobs.
The cadence parser passes 11 corrupted/legacy evidence fixtures; missing or changing requested
cache evidence cannot silently qualify a run.

Strict Vulkan and synchronization validation, actual DLSS RR and synchronized NGX clears are
enabled for the isolated lifecycle tests at 854x480 / near radius four / OMM off:

| Run | Changes | Peak cache bytes | Publications | Stale | Cancelled | Cache deferrals | Terrain deferrals |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `water-cache-lifecycle` | 24 | 10,648,064 | 1,081 | 72 | 4 | 0 | 0 |
| `water-cache-pressure` | 24 | 1,048,576 | 424 | 45 | 6 | 12,263 | 0 |

Both exercise animated height/toggle changes, larger-height fallback, resource reload, Nether
round trip, new-source travel and shutdown with asynchronous work. All exit counters are zero,
and neither log contains a Vulkan/synchronization error. These are resource/race diagnostics,
not FPS or optical acceptance.

The final `water-cache-edits` and `water-cache-pressure-edits` controls additionally create a
water block in a frozen server, wait for the client state, a newer near-build token and a cache
publication (or optional-budget rejection under pressure), restore its saved state, then wait for
the restored client state and completed replacement geometry before travel. Both pass all earlier
lifecycle gates plus `edited=true`. The normal run has 1,677 publications, 28,859,264 peak bytes,
72 stale results and four cancellations; the 1-MiB run has 471 publications, 1,048,576 peak bytes,
44 stale results, five cancellations and 66,608 optional deferrals. Both have zero terrain quota
deferrals and zero native/input/source/pending bytes or objects at shutdown. These checks qualify
source replacement and ownership, not the optical correctness of every edited shoreline.

`perf-water-cache-default-off` separately qualifies the ordinary default path with strict Vulkan,
synchronization validation and live RR. All 2,297 captured frames have finite water false and
zero cache sources, publications or hits. Cache peak/live/input/source/pending/publication counters
are all zero at shutdown, and native terrain bytes are zero. Its timing is diagnostic only.

`perf-water-cache-shadow-compat` also passes the older independent shadow-build tool under strict
validation, with the runtime cache disabled. Its one tight-only phase records 1,195 frames and
956 matched water children; all 956 shadow objects are released, and runtime cache peak and
publications stay zero. This is a tool-compatibility control, not another before/after speed claim.

## Actual runtime selection comparison

`-CompareWaterCache` uses the runtime manager's actual completed objects. The smoke hook selects
the broad original in A and the resolved cache in B, after the real manager has performed its
normal resolution and conservative graphics-use tracking in both variants. No shadow BLAS is
allocated. The scene fingerprint includes original source addresses, primitive attributes and
broad bounds. Every frame verifies the complete source set and selected variant; every eligible
free-top child must have resolved to an actual cache, even in A. Thus this isolates steady-state
address selection with equal retained memory and manager overhead; it does not measure initial
cache-building cost or the entire cost of old versus new binaries.

`perf-water-cache-validation` passes all four phases under strict validation. It retains 543 near
instances, 1,738 LOD sections, 956 water children (942 eligible/cache-ready), 562,783 known field
nodes and zero pending work/misses throughout. It measures 4,179 frames with means A0 11.002 ms,
B1 8.450 ms, B2 8.511 ms and A3 10.918 ms. The source fingerprint is
`449b6884269c5ccba8bca905b8e4ed0db9d015a0161df72e0961f6adc67f2e1c`. All 1,245 cache publications
are managed by the runtime; shadow releases are zero. The cache peak is 29,717,888 bytes and all
shutdown counters are zero. The analyzer sets `validationDiagnostic=true`, `cadenceQualified=false`.
These diagnostic times must not be substituted for the normal-resolution result.

`perf-water-cache-abba` completes the same-process runtime comparison at **1792x1120 output /
896x560 trace, near radius 16, OMM on**, runtime-default RR, one sample and four bounces. Each
phase settles for eight seconds and measures for twenty, with validation, GPU timestamp queries
and CPU CSV instrumentation off. Hardware is RTX 5060 Laptop GPU, NVIDIA driver 616.92.

| Phase | Selected water AS | Frames | Mean ms | p95 ms |
| --- | --- | ---: | ---: | ---: |
| A0 | Broad original | 428 | 46.593 | 49.145 |
| B1 | Runtime cache | 568 | 35.169 | 37.027 |
| B2 | Runtime cache | 562 | 35.535 | 40.204 |
| A3 | Broad original | 430 | 46.446 | 48.533 |

All 1,988 frames retain 6,455 near instances, 2,006 LOD sections, 2,288 water children, 2,241
eligible/cache-ready sources, 614,560 known field nodes, upload generation 259 and zero cache
work/misses/input deferrals. Source fingerprint:
`26ac65ed6dacb150e3c6f3cb900767e6c51ed5e2b29f8f73e0b98911188d5405`. The remaining 47 side-only
children correctly keep their original AS. The analyzer accepts scene/render/source/order/cache
invariants and sets `cadenceQualified=true` for this fixed-scene address-selection comparison.

Frame-weighted means are **46.519 to 35.351 ms, 24.01% lower** (21.50 to 28.29 FPS). Both B
phases beat both A phases on mean and p95. This is a useful steady-state gain but does not make
the reported overall performance issue solved. Do not add it to earlier independent percentages
or present it as a player-world result. Peak tracked cache allocation is 47,346,048 bytes (within
64 MiB), with 2,738 publications; cache inputs/native bytes/owners/pending, field bytes and terrain
native bytes are all zero on exit. No shadow AS was allocated and no terrain quota deferral occurred.

The two captures were visually inspected. RGB code-value RMS is 2.130 for A0/A3, 2.158 for B1/B2,
2.139 for A0/B1 and 2.127 for B2/A3: cross-variant differences are comparable to repeated-frame
noise. This limited screenshot check does not replace raw guide/depth equivalence or optical
acceptance on other shorelines. Both phases run the same compiled shaders from one build;
the `default`/`shared` CSV shader markers are not per-frame cryptographic module fingerprints.

Raw evidence lives under the named `run-smoke/performance-smoke/` directories and root log labels;
accepted summaries and checker fixtures are retained in `artifacts/2026-10-16/water-cache-runtime/`.
Both locations are local ignored evidence, not files bundled in the mod JAR.

The tested runtime JAR is archived as `gysf-water-bounds-cache.jar`, SHA-256
`abb8481087a4abf71ce6217716d64a12d5bbb245782f546cba171e2f09e3741e`. Intersection module:
`f2df8e8b6731b317c4138c80271e3c8b61def2da3da3d1534d8f70cd6b134a31`; hit module:
`a92680a9ac59547da046510ede9de3538062fad9d478707c9ae8f240a12ecfd0`. Smoke classes, comparison
mixins are absent from that JAR. The preceding `339a387` checkpoint's cloud
CI run 38930015072 is confirmed successful; it is separate from this runtime candidate's evidence.

## Reproduce

With JDK 25, Vulkan SDK 1.4.341.1, Slang 2026.18 and DLSS SDK 310.9.1 staged, no other Minecraft
process running, and the paired `d4097ba` provider built:

```powershell
.\scripts\Test-WaterCache.ps1 -Label fresh-cache-lifecycle
.\scripts\Test-WaterCache.ps1 -Pressure -Label fresh-cache-pressure
.\scripts\Test-Performance.ps1 -Label fresh-cache-abba -RenderDistance 16 -Width 1792 -Height 1120 `
  -InitialSeconds 90 -SettleSeconds 8 -MeasureSeconds 20 -Presets '0,0,0,0' -Qualities '0' `
  -FiniteWater -CompareWaterCache -EnableOmm
python scripts/Analyze-Performance.py run-smoke/performance-smoke/fresh-cache-abba/frames.csv `
  --output fresh-cache-abba.json
```

The lifecycle runner explicitly enables validation/synchronization checks. For cadence, disable
validation layers, GPU timing and CPU instrumentation. Add `-ValidateVulkan` plus synchronization
validation/NGX tracing for the diagnostic control instead. Existing `-CompareWaterBounds` still
uses independent shadow builds and explicitly disables this runtime cache.

Natural/coarse shoreline quality, animated long-travel temporal quality, the full DLSS/native/SVGF
matrix and player-world visual/performance acceptance remain open before default finite-water
activation. The artificial 1-MiB run also exposes many failed admission attempts; bounding those
attempts per frame is a follow-up CPU-pressure optimization, not a terrain-distance fallback.
This bounded cache does not by itself establish those remaining results.
