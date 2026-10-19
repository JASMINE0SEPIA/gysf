# Disk-only LOD restoration replay

The production fix is in vooxxy Vulkan.15 (`bd20c895`). A saved standalone
Blaze3D preference previously prevented the native Vulkan LOD provider from
starting under gysf. The bridge then returned before the API could retry startup.
The fix preserves the standalone preference, starts the native provider when an
external Vulkan renderer is active, and retries after world entry. No storage
format or external API change is involved. That checkpoint used gysf 0.7.4.

`LodReentryCapture` is a development-only replay, selected with
`-Dgysf.smoke.reentry=true`. It requires an isolated physical save copy at
`saves/gysf-reentry` in the smoke run directory. Use the original world's active
mod set as well: stored block states must remain available. Never run this
harness against the player's live save directory. It changes the copy's time,
game mode, camera, render distance and ingestion setting.

The normal replay holds a fixed spectator camera, waits at a configurable high
view distance (`gysf.smoke.reentry.highDistance`, 32 in this validation), lowers
it to six chunks, disables Voxy ingestion, disconnects, and reopens the same save
without travelling through the missing far terrain. It requires fresh scene
epochs and verifies nonempty source meshes beyond the near distance, resident
GPU batches, and published far coverage. Screenshots and JSON states are saved
under the smoke run directory. Camera input is suppressed only in this replay.

For a separate game-process restart, retain the copied cache and disabled
ingestion, then add `-Dgysf.smoke.reentry.cold=true`. Adding
`-Dgysf.smoke.reentry.awaitCompletion=true` waits for both provider refinement and
the consumer build queue to drain and remain idle for 100 ticks, with a
10,000-world-tick timeout. This catches
a rebuild that only makes initial progress and then stalls. Run the log gate
with `-RequireLodReentry -RequireNgxClearSync`; this also rejects Vulkan validation
and synchronization reports and requires zero native LOD allocations at exit.

The Greenfield v0.5.4 fixture contains a complete physical copy of the player's
save, Sodium 0.9.3-beta.1, and the current active mod set. Dynamic FPS is omitted
only from the fixture to avoid background throttling. Chunksmith remains disabled.
The copied Voxy database has 49,675 valid L0-L4 records with complete nonempty
parent and child links. No original save or cache is modified.

Vulkan.14's separate cold-start baseline restores zero historical source meshes,
GPU batches and sampled far coverage. Vulkan.15 passes the 32 -> 6 -> disconnect
-> reopen replay with ingestion disabled: 2,017 nonempty far sources, 559 resident
GPU batches and 236 sampled far sections after 900 world ticks. Publication is
progressive; those counts do not represent completion of the entire rebuild.
The candidate and final packaged provider match in all 496 runtime class hashes.

Evidence, screenshots, package hashes and deployment receipts are retained locally
in `artifacts/2026-10-19/lod-reentry/`. The final packaged JAR also receives the
separate cold replay with the pending queues required to drain.

The final `bd20c89` JAR passes the cold replay with 2,412 compacted GPU batches,
3,151 sampled far sections, 2,017 nonempty far sources and an empty build queue.
Ingestion remains disabled and temporary distance reduction remains zero.
Reconstruction takes about 225 seconds with Vulkan validation enabled, while
intermediate checkpoints steadily increase coverage. This timing describes this
fixture only. The Vulkan/NGX gate passes; native LOD allocations return to zero
(1,418,030,608-byte peak, zero deferrals). A second cold replay clears weather on
the copy and waits another 100 idle ticks for a readable final visual check. It
also passes with the same 2,412 batches and 3,151 sampled far sections; the saved
screenshots show the previously missing streets and buildings restored beyond
the six-chunk near distance. Vulkan validation remains clean and native LOD
allocations return to zero (1,418,030,896-byte peak, zero deferrals).

The final Vulkan.15 package was deployed with hash verification and a Vulkan.14
backup. All 146 protected configuration/save/cache files and the other 16 mod
files retain their pre-test hashes. gysf 0.7.4 and Sodium remain unchanged.

## gysf 0.7.6: streaming recovery

The provider startup fix restores access to disk data; it does not by itself
make the ray-tracing consumer finish rebuilding it promptly. In the observed
0.7.5 player trace, 40 of 41 plans were cancelled in 94 seconds. Even the
ingestion-disabled camera replay repeatedly discarded work. The changes on
`jasmine/lod-streaming-recovery` address the consumer in three steps:

* Camera changes retarget the remaining Voxy work inside the same world epoch.
  Valid completed batches and the published proxy survive. In-flight work drains
  before a new immutable plan takes ownership; world changes, real failures and
  memory pressure still invalidate obsolete work.
* A 256 MiB CPU snapshot LRU retains off-view meshes. Provider revisions with
  identical geometry, LOD, water and relevant material/tint data retain their
  consumer identity. Changed blocks and biome colours invalidate the affected
  data. GPU detail reuse also requires the matching content revision.
* A bounded window admits packing, upload, BLAS construction and compaction
  together: 16 batches and 64 MiB of estimated transient data by default.
  `gysf.rt.lodBuildInFlight` (1..32) and `gysf.rt.lodBuildMiB` (16..256) override
  those limits. One oversized batch can proceed alone, subject to the existing
  native allocation checks. Completed GPU batches publish together per drain.

Batching exposed the old automatic 1,782 MiB soft ceiling on this machine: it
reduced the requested 1,024-block range even with driver headroom available.
With a valid driver budget, the automatic limit now allows half the heap, capped
at 4 GiB. The original fallback limit applies without that information; explicit
user limits and the per-allocation driver reserve/growth checks are unchanged.
Full-range replay acceptance requires zero temporary distance reduction.

The final build passes 259 Java tests, the Minecraft ABI check and JAR checks.
The log-gate regression suite passes 40 positive/negative cases. The package
matches all 405 production classes, 32 SPIR-V shaders and the mixin configuration
used by the runtime replay. The installed Vulkan.15 provider remains unchanged.

All streaming runs use independent physical copies of the Greenfield fixture,
the same active mods, Vulkan synchronization validation and real NGX ray
reconstruction. The camera replay turns through four headings, travels 512
blocks away and back, then places and removes a diamond block in the copy.
The edit assertion checks both CPU revision/content and published GPU content;
an entirely empty section may correctly disappear rather than retain an empty
mesh. `travel-final.log` finishes with 5,349 resident batches, 3,623 sampled far
sections, zero camera cancellations, no pending builds and the full requested
range. It records 4,041 CPU cache return hits, 449 resident batch reuses and a
16-batch peak window. Native LOD allocations return to zero on exit with no
deferrals (3,001,625,184-byte peak).

The final 0.7.6 code also passes `reentry-final.log`: request 32 near chunks,
lower to six, disconnect, reopen with ingestion disabled, turn, travel and edit.
World/provider epochs change across the transition; camera changes do not cancel
valid work. It finishes with the same 5,349 resident batches and 3,623 sampled far
sections, zero view cancellations, no pending work and no far-distance reduction.
The cache records 6,429 return hits and 453 resident batch reuses. Real driver
pressure occurs once during the initial high-near-distance phase: the existing
near-memory controller temporarily changes 32 to 24 chunks and LOD allocation
waits, preserving the published fallback. This is not a claim that 32 near chunks
fit this GPU. Recovery completes, Vulkan/NGX validation passes, and native LOD
allocations return to zero (2,898,226,816-byte peak, one deferral).

The final fixed-camera cold-process replay (`cold-final.log`) reads historical
data with ingestion disabled, near distance six and requested LOD range 1,024
blocks. At 20.79 seconds it has 2,745 resident batches; at 30.77 seconds, 4,737.
All 5,349 batches finish at 33.61 seconds after world entry, with 4,954 nonempty
far sources, 3,623 sampled far sections and no pending work or distance reduction.
It remains idle for the acceptance window. Vulkan/NGX validation passes and
native LOD allocations return to zero (2,421,291,152-byte peak, zero deferrals).
The final screenshot was inspected and shows the distant streets and buildings
restored. These are separate process launches using an existing fixture database;
the operating-system file cache was not flushed.

Artifacts, screenshots, package verification and deployment records for this
stage are retained locally in `artifacts/2026-10-19/lod-streaming/`. Early
`travel.log` and `travel-fixed.log` runs failed an overstrict replay assertion
about absent air sections; they are not counted as successful acceptance runs.
`batch.log` is the old-budget diagnostic and is not full-range acceptance.

This is an in-process streaming/cache improvement. It does not persist converted
meshes or BLAS objects across game processes: a fresh process still reconstructs
them progressively from the Voxy database. Replay timings include validation
overhead and are not a general FPS benchmark or a directly comparable speedup
against the older 512-block replay.

The verified `gysf-0.7.6-dev+mc26.3.jar` was deployed to
`A:\Programs Files too\PCL\.minecraft\versions\26.3\mods` with SHA-256
`A6504A138E6082761AC85378BB4B540A49440FFCECB78BB9F680530686D66893`.
The old 0.7.5 JAR is backed up under this stage's `previous-mods/` directory.
All 90 pre-test inventory entries matched before replacement; afterwards the
73 protected non-mod files and other 16 mod-folder files retain their hashes.
The provider, Sodium, original save/cache and configuration were not changed.
