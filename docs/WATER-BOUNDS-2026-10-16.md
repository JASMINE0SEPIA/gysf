# Water bounds: matched-scene diagnosis

The subsequent bounded asynchronous runtime implementation and its separate acceptance evidence
are recorded in [WATER-CACHE-2026-10-16.md](WATER-CACHE-2026-10-16.md). This document describes the
earlier shadow-build diagnosis; its unchanged-JAR statement applies to that checkpoint.

The `460268f` renderer always expands free water tops by 2.2 blocks, covering the maximum
wave-height slider of four. At strength one, the authored height spectrum has an analytic upper
bound about 0.507327742 blocks: each positive component is at most `Q/k`, multiplied by authored
strength 0.9. Its meander changes phase, not this bound. A 0.55-block envelope therefore covers
every phase at strength one; sides/bottoms remain flat. The existing unit contract independently
parses the shader amplitudes to check the maximum-strength production envelope.

This checkpoint adds a **smoke-only comparison**, not tighter shipping bounds. The shipping JAR
and its behavior are unchanged. No PCL package, settings or world was modified. Finite water
remains experimental and default off.

## Controlled mechanism

The worker-side probe captures the exact packed water input used for each original BLAS. Its
copy only changes the maximum Y bound of a free top; triangle positions, shoreline data,
material tables, original buffers and shaders stay shared. After the scene settles, it builds a
second water BLAS for every resident water child. The TLAS selects either complete set at idle
phase boundaries. Both sets remain alive throughout the comparison, with identical shader code.

Each recorded frame verifies that the TLAS writer observed every frozen source water child and
the requested variant. A SHA-256 fingerprint covers the active source addresses plus original
primitives and broad input bounds. Source retirement invalidates the comparison. The diagnostic
forces seven-component, strength-one frozen waves and rejects changing parameters. The analyzer
requires the expected variant sequence, immutable source identity, child count and render
settings. Negative fixtures reject wrong order, source drift, unsafe strength, missing children
and mismatched internal rendering size. The earlier paired-shader comparison still passes its
analyzer unchanged.

Alternate builds use host-write-to-build and build-to-AS-read barriers. Setup submits and waits
outside measured windows; scratch/input buffers are freed only after completion. Both BLAS sets
remain allocated while timed, avoiding allocation/retirement changes between phases. Teardown
waits for the probe's GPU work and reports zero alternate BLAS objects. These deliberate waits
are test infrastructure, not a suitable per-frame implementation.

## Validation control

`perf-water-bounds-validation`: 854x480 output / 427x240 trace, near radius 4, OMM off, finite
water on, live DLSS RR. Vulkan and synchronization validation are explicitly enabled, along with
NGX synchronized-clear tracing. The four phases measure 4,194 frames in total.

| Phase | Bounds | Frames | Mean ms |
| --- | --- | ---: | ---: |
| 0 | Broad | 935 | 10.683 |
| 1 | Tight | 1,178 | 8.479 |
| 2 | Tight | 1,176 | 8.490 |
| 3 | Broad | 905 | 11.037 |

All phases retain 543 near instances, 1,738 LOD sections and 562,783 known field nodes. The
matched water set has 956 children, 715,434 triangles and 665,344 free tops. Its source fingerprint
is `17b5f41571345a09f9645ac0e6869778a4eccc25caff545045aea77eef2fbddb`. Log acceptance with
`-RequireNgxClearSync` passes, with no Vulkan/synchronization error report. All 956 alternates,
native LOD bytes and water-field bytes are released. This is diagnostic timing, not production
cadence (`cadenceQualified=false`).

## Normal-resolution A-B-B-A

`perf-water-bounds-abba`: 1792x1120 output / 896x560 trace, near radius 16, OMM and finite water
on, runtime-default DLSS RR, one sample and four bounces. Validation, GPU timestamp queries and
CPU statistics are off. Each phase settles for eight seconds and measures for twenty seconds.

| Phase | Bounds | Frames | Mean ms | p95 ms |
| --- | --- | ---: | ---: | ---: |
| 0 | Broad A | 439 | 45.427 | 47.723 |
| 1 | Tight B | 590 | 33.886 | 35.757 |
| 2 | Tight B | 592 | 33.727 | 35.535 |
| 3 | Broad A | 438 | 45.576 | 48.159 |

All 2,059 measured frames retain 6,455 exact near instances, 2,006 LOD sections, 614,560 known
field nodes, upload generation 280 and zero pending requests/builds. The matched water set has
2,288 children, 1,130,012 triangles and 1,059,004 free tops. Source fingerprint:
`c1c56888e61ccaa85e9a4446d2ba428eb07f3c908d41c1e468f8db8ea7217704`.

The analyzer accepts source/scene/render/order invariants and sets `cadenceQualified=true`.
Frame-weighted means are **45.501 to 33.806 ms, 25.70% lower in this fixed scene** (21.98 to
29.58 FPS). Both repeated tight phases beat both broad phases on mean and p95. Do not add this
percentage to earlier independent experiments or present it as delivered player-world speed.
The extra AS allocations add roughly 44 MiB to the renderer's allocation accounting in this
control; both timed variants retain that same allocation set. There is no distance reduction.
All 2,288 alternates and tracked field/native LOD bytes return to zero at shutdown.

Static capture differences are comparable to repeat noise: RGB code-value RMS is 2.159 for
A0/A3, 2.139 for B1/B2, 2.160 for A0/B1 and 2.092 for B2/A3. Captures were visually inspected.
These image statistics are a limited cross-check, not depth/guide equivalence or complete optical
acceptance. Animated travel, changing wave controls and natural shoreline cases remain open.

## Reproduction and next implementation

Use `scripts/Test-Performance.ps1` with fresh labels, the above dimensions/distances, four zero
presets and `-FiniteWater -CompareWaterBounds -WaterBoundsSequence '0,1,1,0'`. Add `-EnableOmm`
for the normal-resolution control. `-ValidateVulkan` plus the explicit synchronization-validation
environment flag reproduces the small diagnostic. Existing Vulkan/NGX/JDK/Slang paths apply.
Raw CSVs, metadata and phase captures are in the matching `run-smoke/performance-smoke/` labels;
summaries, negative fixtures and image metrics are under
`artifacts/2026-10-16/water-bounds-geometry/`. These raw directories remain ignored by Git.

Full Gradle `check` and smoke compilation pass (207 Java tests). Smoke comparator classes and
mixins are absent from the mod JAR. It still hashes to
`61656a362570c26f101f63a09c01957d2ae6424a2f8673534a14af2e88187423`, with intersection module
`f2df8e8b6731b317c4138c80271e3c8b61def2da3da3d1534d8f70cd6b134a31`.
The `460268f` baseline's cloud CI also completed successfully, separately from this GPU evidence.

The next runtime candidate should asynchronously cache a tighter water BLAS while retaining the
maximum-height original as an immediate fallback. If the current setting exceeds a cached bound,
use the original immediately until a matching cache is ready; the shader's requested wave height
must keep working throughout. Preserve the same source/material/primitive ownership and reject
stale completions with owner identities/generations, not a recycled device address alone.

Cache allocations need a bounded budget and pressure fallback. Destruction must follow actual
graphics-use completion, including off-queue recorded work and overlay references, rather than
assuming `waitIdle()` alone proves every possible future use has finished. Validate rapid 0..4
height changes, toggles, edits, source replacement/cancellation, travel, reload, dimensions,
memory pressure and shutdown before treating the measured potential as a shipping optimization.
