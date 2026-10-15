# Runtime memory and DLSS investigation

The player reported slow rendering, incorrect near/far boundaries or shapes, and
lower performance with DLSS enabled. These remain separate acceptance targets.
The isolated scene is not a copy of the player's world, and none of these checks
changes the installed PCL mod pair, player settings or saves.

## Evidence and changes

The player log recorded 7,497 MiB process device-heap usage against a 6,999 MiB
driver budget. RT allocations accounted for 6,198 MiB, the provider owned about
612 MiB, and published native LOD used only 42 MiB. Repeated LOD plans rebuilt
roughly 300 batches before even small allocations were rejected. The temporary
LOD distance reached 128 blocks. This proves memory pressure and wasted retry
work, but does not establish the cause of every reported seam or shape error.

The near-terrain working set now responds to actual driver headroom while an
enabled Voxy provider is available. It contracts by a quarter at most every two
seconds, retaining at least four chunks. Missing-section growth pauses under
pressure; existing edits can still replace a section. Ten seconds of sustained
allocator headroom permits gradual recovery to the requested distance. The player's saved
distance is unchanged. The existing exact published-readiness mask controls which
LOD hits are suppressed; the vanilla requested radius is not an ownership mask.

After a rejected LOD allocation, refresh admission checks the failed request
against current headroom before making another CPU/BLAS plan. The preview creates
no allocation ticket and does not increment the deferral counter. Published LOD
remains active while allocation is deferred.

Enabling frame statistics exposed missing registered stage names and a counter.
Those names are now registered, with a regression scan covering literal call
sites. The CSV contains CPU recording times, not GPU execution times.

## DLSS model selection

The previous SDK pin was 310.7.0. Its RR header explicitly describes Preset F as
falling back to default behavior. NVIDIA's
[310.9.1 release](https://github.com/NVIDIA/DLSS/releases/tag/v310.9.1) adds RR2/F;
the updated pin is commit `374959484e79a640feaba44c93ac8cfb0a03f5b5`.
Creating a feature with requested preset 6 on the old runtime did not prove that
RR2 was executing. Driver overrides and OTA selection can also affect the model.

The UI now distinguishes runtime Default (0), explicit earlier Transformer D (4)
and DLSS 4.5 RR/F (6). It no longer labels Default as "DLSS 3.7". The native shim
also overwrites all six quality-mode hints when returning to Default: NGX's
capability parameter block outlives feature destruction, so skipping the zero
writes retained the previous explicit preset. A native CPU regression exercises
F -> Default -> D -> F -> Default against a persistent parameter block.

## Validation

- 185 JUnit tests passed, including the captured over-budget and retained-pool
  traces, hysteresis, admission previews and statistics registration.
- The native preset-transition CTest passed, built with MSVC and SDK 310.9.1.
- A joint Vulkan/synchronization-validation run completed 5,587 RT frames,
  resource reload, a Nether round trip and the scripted movement route. Forced
  one-MiB LOD allocation pressure caused one deferral and one distance reduction;
  the original distance recovered before reload. Published fallback remained for
  199 sampled ticks. Shutdown native LOD live bytes were zero. No Vulkan validation
  error or synchronization hazard was found in that run.
- A separate real driver-pressure run with Vulkan/synchronization validation
  contracted the near window 32 -> 4 -> 32 chunks. Published near instances went
  25,350 -> 576 -> 13,216 (still filling after recovery); 227 checks found the
  shader ownership mask consistent with the published TLAS inputs and confirmed
  empty sections. No Vulkan validation error or synchronization hazard was found;
  native LOD live bytes were zero at exit. This checks ownership metadata and
  resource lifetime, not complete visual equivalence of coarse and exact meshes.

## Repeatable isolated comparison

Build the native shim and set JDK 25, VULKAN_SDK and DLSS_SDK first. Then run:

```powershell
.\scripts\Test-Performance.ps1 -NearMemoryPressure
```

The test uses 32 chunks and 1792 x 1120 by default, 1 SPP, four bounces, OMM off,
a fixed camera and wave phase, and a frozen isolated server after initial loading.
Vsync and AFK throttling are disabled, with a 260 FPS cap. Following three minutes
of warmup, the optional pressure stage makes up to three GiB of unused device
allocations, releases them, and checks contraction, retirement and recovery.
The model sequence is Default, D, F, native-resolution SVGF, F, D, Default. Each
switch gets 15 seconds to settle and 20 seconds of measured wall-clock cadence.
The revised harness also waits for ten seconds without near build-token changes,
LOD packing or LOD publication before starting the comparison. CSV scene-version
counters expose later rebuilds, even when instance counts happen to remain equal.
Use `-EnableOmm` for an independent OMM-on run; its actual device capability is
checked, and classification statistics are recorded. Do not combine these runs
into an equal-work throughput claim if the memory controller changes residency.

`frames.csv` includes exact-terrain instance count, near radius, published LOD
count and memory usage to expose scene drift. The measured interval includes
GPU waits, presentation and CPU work; it is not an isolated RR-pass GPU timer.
Native-resolution SVGF traces four times as many pixels as DLSS Performance at
this display resolution, so this comparison measures the complete player-facing
choice rather than equal-resolution denoiser cost. Validation-layer runs must
not be presented as normal gameplay throughput.

Summarize a completed run with `python scripts/Analyze-Performance.py <frames.csv>`.
The analyzer rejects changed camera/screens, scene counts or build/publication
versions by default. `--diagnostic` can summarize missing/changing geometry
versions, but keeps `comparisonAccepted` false; it cannot override changed input
or invalid frame data. Older captures without scene-version columns require that
diagnostic option, with any log-supported qualification described separately.

Raw logs, CSVs, screenshots and native hashes are retained under the ignored
`artifacts/2026-10-15/memory-working-set/` directory. The earlier 310.7 trial had
substantial LOD loading drift and sticky default hints; its numbers are diagnostic
evidence only, not a claimed speedup or a valid D-versus-F comparison.

The first 310.9.1 trial passed the device-pressure lifecycle check (32 -> 4 -> 32
chunks; 25,156 -> 577 -> 18,608 published near instances during recovery; all native
LOD released at exit). However, screenshot review found an open inventory and a
changed view. Its frame rates are invalid as a fixed-scene comparison, even
though the later geometry counts were steady. The revised smoke-only harness
locks camera/input and rejects nonempty screens or changed player poses during
measurement. It also records camera position, yaw, pitch and screen status in
each CSV row. An interrupted or invalid run is never a performance acceptance.

## Open acceptance

Visual ownership during shrink/recovery, the user's specific distorted boundary,
OMM-on performance, other worlds, and temporal image quality still need separate
qualification. The finite water primitive encoder and native RT fixture are
validated independently; the procedural water patch is not integrated into the
game pipeline yet. No whole-renderer or complete near/far-water acceptance is
claimed by the memory or model-selection changes.

## Locked-camera comparison with OMM disabled

`dlss-31091-locked-resume-32` completed with fixed camera position
`(0.5, 141.61999988555908, 0.5)`, player yaw 0, pitch 20 and no open screen in
every sampled frame. All phases kept 25,352 exact instances, 2,006 LOD instances
and a 32-chunk near window. Phase 0 overlaps the last LOD rebuild (final
publication 21:51:01 local time) and is excluded. Phases 1-6 had no later LOD
planning/publication messages; their camera and instance counts passed the CSV
checks. This earlier CSV lacks the newly added per-frame scene-version counters,
so that part is supported by the retained log rather than inferred from counts.

| Phase | Reconstruction | Mean frame ms | Mean FPS |
| --- | --- | ---: | ---: |
| 1 | D, Performance | 22.289 | 44.86 |
| 2 | F, Performance | 22.876 | 43.71 |
| 3 | Native-resolution SVGF | 67.889 | 14.73 |
| 4 | F, Performance (return) | 23.181 | 43.14 |
| 5 | D, Performance (return) | 22.853 | 43.76 |
| 6 | Runtime Default, Performance (return) | 22.599 | 44.25 |

The test did not reproduce DLSS being slower than native-resolution SVGF in this
OMM-off scene. F was slightly slower than D in both orders; this is not evidence
of a large 4.5 performance failure. These are one-machine full-frame measurements
at different trace resolutions, not isolated denoiser GPU timings or an image
quality equivalence claim. Native LOD live bytes were zero at shutdown.

## OMM-on reproduction

`dlss-31091-locked-omm-32` verified the device's opacity-micromap feature and
recorded actual level-4 classification work. The RT allocation working set grew
to about 4,781 MiB, driver headroom tightened, and the memory controller contracted
the requested 32-chunk near window to four chunks. After retirement, RT live
allocations were about 515 MiB, but the driver still reported about 6,450 MiB
process usage. The near window and temporary 256-block LOD limit did not recover
within that run. This exposed the retained-pool accounting problem described
below; its frame rates do not qualify the later fix.

The seven model phases passed camera, screen, geometry-count and geometry-version
checks at **576 near instances and 560 LOD instances**, with no background builds
or publications during measurements. RR ran around 58-60 FPS and native SVGF
around 9 FPS, but these rates must not be compared with the OMM-off full-radius
scene as an OMM speedup: most exact geometry had been removed. Native LOD live
bytes returned to zero on exit. An allocator/system diagnostic is retained
separately to distinguish reserved blocks from external or delayed driver usage.

## Retained allocator storage and recovery

`omm-memory-allocator-diagnostic` sampled the current RT allocator's VMA
statistics separately from the process-wide driver estimate. After the old
controller reached four chunks, it retained 5,210 MiB of device blocks with only
647 MiB in live allocations: 4,563 MiB was unused storage available to this
allocator. A diagnostic device-idle wait did not remove it. Raw driver headroom
alone was therefore preventing reuse of already allocated blocks. This evidence
does not identify a live-geometry leak or prove that other driver allocations
have the same behavior.

Admission and near-window hysteresis now use raw budget headroom plus half of
the current RT allocator's unused device-block storage. The other half provides
slack for fragmentation and incompatible memory types. Cached LOD growth is
still charged, native LOD retains its independent quota, and all device-preferred
buffer allocations use VMA's `WITHIN_BUDGET` flag to constrain physical growth.
The credit is specific to this allocator, cannot be spent by the Voxy provider,
and disappears as its blocks fill. It estimates usable space; it is not a
guarantee that every native allocation can succeed.

The updated `pooled-headroom-pressure-validation` run deliberately held unused
device buffers with Vulkan/synchronization validation active. Near radius changed
32 -> 24 -> 32, exact instances 25,350 -> 17,544 -> 25,403, with retirement
observed and 226 successful published-ownership checks. The temporary LOD limit
also cleared. No Vulkan validation error or synchronization hazard was found;
native LOD live bytes were zero at shutdown. The older 32 -> 4 -> 32 pressure
run above remains a separate record of the raw-headroom policy.

An intermediate OMM run, `pooled-headroom-omm-32`, stopped collapsing to four
chunks but repeatedly tried to recover 22 -> 30 while the far replacement was
still being built. The partial far scene temporarily looked comfortable; its
later allocations forced another 30 -> 22 contraction. The run was interrupted
through the isolated Minecraft window, and has no accepted frame-rate result
or observed native-zero shutdown result. A successful Gradle process exit alone
does not count as smoke-test completion.

Near recovery now requires both actual build queues to be idle, including
invalidated worker/GPU tasks still retiring allocations. Busy builds reset the
ten-second recovery window, while genuine pressure can still contract the near
window immediately. This prevents partially built geometry from serving as
evidence that the final scene fits. It does not guarantee that the full requested
radius fits on every GPU. The harness also checks the requested completion marker
and rejects a comparison that cannot settle within five extra warmup minutes.

`settled-headroom-omm-32` completed with actual OMM enabled. It reduced near
radius once, 32 -> 24, and retained the full 2,006-instance LOD selection after
the temporary limit cleared. All 1,113 measured frames kept 17,596 exact near
instances and constant near build token/table, LOD revision, pack count and
publication count, with the fixed camera and no screen. Live RT allocations
settled near 3,763 MiB, native LOD near 612 MiB, unused RT reserved storage near
1,192 MiB, and driver process usage near 6,229 MiB. Shutdown native LOD live
bytes were zero. The intermediate repeated shrink/rebuild cycle and the old
four-chunk stall did not recur within this run.

Runtime-Default RR/Performance measured 17.958 ms mean (55.69 FPS), 19.065 ms
p95 and 19.739 ms p99 in this single phase, with validation layers off. This is
a completed fixed-scene cadence observation, not a controlled speedup against
the earlier four-chunk OMM run or the 32-chunk OMM-off comparison: residency
differs. It also does not establish long-travel stability or repair the known
finite-water integration gap.

The final `settled-headroom-pressure-steady` run held 1,664 MiB of unused device
buffers for 45 seconds with Vulkan/synchronization validation active. Radius
changed 32 -> 24 -> 32 and exact instances 25,352 -> 17,546 -> 25,517. Completion
now requires ten seconds without active near/LOD builds after full-radius
regrowth, so the test cannot exit on the first tick that queues an unfilled
ring. The full 2,006-instance LOD selection remained published; 232 ownership
checks passed. No Vulkan validation error or synchronization hazard was found,
there were no native LOD allocation deferrals, and native LOD live bytes were
zero at shutdown. The earlier `settled-headroom-pressure-validation` capture
returned to radius 32 at 24,315 instances before refill finished; it establishes
initial recovery and lifetime behavior, not this stronger steady-refill check.

The retained Windows candidate is `gysf-0.7.0-dev+mc26.3-e03efaa.jar`, production
source `e03efaaa93ef89e7fce61a2683f60be92819b802`, SHA-256
`6A724D4F51745034E0C0A6FEEE1A3DCE398363BA9459FCC572304045AD48DC7A`.
It is an experimental memory/model-selection checkpoint, not the installed PCL
artifact or a completed water-surface integration. Its bundled shim and RR DLL
were inspected in the actual JAR and match the tested 310.9.1 files.
