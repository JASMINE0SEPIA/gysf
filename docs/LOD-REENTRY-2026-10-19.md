# Disk-only LOD restoration replay

The production fix is in vooxxy Vulkan.15 (`bd20c895`). A saved standalone
Blaze3D preference previously prevented the native Vulkan LOD provider from
starting under gysf. The bridge then returned before the API could retry startup.
The fix preserves the standalone preference, starts the native provider when an
external Vulkan renderer is active, and retries after world entry. No storage
format or external API change is involved. The gysf production mod stays 0.7.4.

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
the copy and waits another 100 idle ticks for a readable final visual check.
