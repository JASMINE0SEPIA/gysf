# Visible terrain and cloud continuity

The player's six captures `2026-10-16_21.32.14.png` through `21.33.01.png` show missing terrain,
large coarse textured blocks, a near/LOD colour boundary and an almost closed cloud ceiling.
The immediate acceptance target is continuous, natural-looking play. FPS tuning and millimetre-scale
water optics are deferred; water must retain joined surfaces without gaps.

## Changes

* Clouds now require both the gysf toggle and Minecraft's cloud setting. OFF clears the visible,
  reflection and shadow cloud state. Classic follows FAST's flat sheet and FANCY's four-block boxes;
  the volumetric depth slider no longer extrudes classic cells to 55 blocks at its default 0.5.
* Textured Voxy tint now uses the same normalized authored multiplier as near Fabric terrain.
  Both shaders already linearize the sprite; only the distant path had also curved the biome tint.
  Water's normalized optical tint is unchanged.
* Near snapshots require the same loaded section identity. A neighboring column may be cached
  outside the RT desired window, so its unload is not necessarily seen by the window's eviction
  path. Reloading the same coordinates must not revive an old palette or AIR snapshot.
* Window exit and near-memory contraction retain already published exact geometry until a
  complete published Voxy source covers that 16-cube. The ready mask expands to include these
  retained sections, so the shader does not draw both owners. Retention is bounded by the nominal
  horizon and at most 1,024 horizontal blocks. Re-entered sections rebuild from fresh snapshots.
* Driver headroom reserved for near streaming defers further LOD allocation without also halving
  the far horizon. Near already has a pressure controller. Actual LOD-budget/native allocation
  failures keep the existing bounded distance degradation.
* Same-world video-option/F3+A invalidations rebuild near terrain in place. World identity and
  resource/material epoch changes still clear the old scope. Provider epoch changes also discard
  old textured proxy IDs before binding a new provider atlas; model IDs are local to that epoch.
* Provider Vulkan.13 retains previously drawn finer branches when new siblings are discovered.
  A growing child mask no longer forces the entire existing area back to a coarse parent while
  the new sibling is pending. Actual child removal still invalidates stale coverage.
* New player captures at 22:12:35/38 reproduce another cause: the provider already selected detailed
  leaves, but the RT proxy still drew bootstrap parents admitted seconds after world entry. Replacing
  a 512-block parent atomically required thousands of fine batches. The provider now marks transient
  refinement fallback scenes. The consumer waits before admitting those snapshots and keeps existing
  terrain; intentional distant coarse targets are still admitted. This avoids creating the oversized
  replacement group in the first place, rather than deleting a coarse fallback and exposing a hole.
* The lifecycle replay also exposed a 26.3 player-head cache issue after a resource reload: skin render
  infos retained a borrowed texture view that TextureManager had closed. Displaying the next player
  glyph used that invalid view in GUI text descriptors. A small RenderInfo mixin clears only a closed
  cached view, allowing the original getter to resolve the current texture. Live views stay cached.

The original player log records near 32 -> 24 -> 18 around 21:31:44, alongside a driver-headroom
deferral and temporary far-distance reduction. Its tracked LOD bytes were well below the LOD cap.
The combined contraction could withdraw the very fallback needed after exact geometry retired.
These are targeted policy/lifetime corrections, not a claimed FPS improvement.

## Validation and limits

Consumer checks pass 228 tests, including cloud switches/depth, near-compatible tint, reloaded
snapshot identity, signed 3D coverage membership, bounded near handoff and pressure classification.
Provider checks pass 73 tests, including twenty repeated growing-root snapshots retaining the
same fine leaves until the new sibling becomes ready, removal invalidation and refinement metadata
surviving atlas growth without changing older scene constructors.

`PlayerVisualCapture` replays the supplied cameras against a copied save under a separate run
directory. It opens only `saves/gysf-player-visual`, records cloud OFF/FAST/FANCY, tests near
distance 6 -> 32 or starts directly at 32, flies to the negative coordinates and returns. Post-render ownership probes
compare the actual TLAS sections/empty sections against the shader mask at one-second intervals.
Original saves, configs and mods are not used as writable test directories.

Early baseline/fix1/fix2 replays inherited the normal smoke horizon of 512 blocks. The first fix
run was additionally throttled by Dynamic FPS when its window lost focus; its incomplete far
build is not accepted as settled visual evidence. The `handoff` and later replays use the player's
1,024-block external horizon, Java heap 6,860 MiB and Fabric API 0.163.0+26.3. Only the isolated
copy omits Dynamic FPS to prevent background throttling of the replay. No FPS comparison is made.

The final replay `player-visual-final.log` completed 7,621 rendered frames and 249 post-render
ownership checks, with eight captured stages all reporting `unwantedCoarse=0` in the actual RT
proxy. The supplied large leaf/grass/stone shapes are absent from the inspected matching camera
captures, including after moving away and returning. Resource reload and Nether/Overworld travel
complete with zero VUID or synchronization reports under Vulkan validation layer 1.4.363.

The two preceding lifecycle attempts failed the strict log gate on stale player-head GUI image
views; they are retained as failed evidence. The final run explicitly exercised the closed skin
view recovery, with no suppressed reports. Native LOD allocation returns to zero at shutdown
(535,124,112 bytes peak, four driver-headroom deferrals); those deferrals leave the far distance
limit at zero, meaning the requested 1,024-block horizon stays in effect. Up to 16,121 exact
sections were retained during handoff and 1,420 completed handoffs before the dimension reset.
This favors continuity and is not a 32-chunk performance qualification.

The packaged consumer matches all 392 runtime mod classes, 32 SPIR-V modules and the mixin
configuration. Artifact hashes, source revisions, logs, before/after captures and the previous
installed pair are recorded in the local checkpoint and deployment receipt.

The specific copper-textured variant is not independently reproduced; the epoch guard and
finer-branch handoff address distinct mechanisms. Full player visual acceptance and broad water
surface/optics acceptance remain separate from this terrain/cloud route and its checked invariants.

## Return-trip regression, 23:45 captures

The player's 23:45:32 and 23:45:37 captures show large grass/leaf/stone boxes again after moving
away and returning. Vulkan.13 is confirmed installed. This session has no LOD allocation deferrals
or temporary distance cap; the defect cannot be attributed to that earlier memory contraction.
The preceding short replay did not qualify coarsening a previously loaded region and returning.

Two consumer scheduling problems remain after the bootstrap admission correction: a legitimate
distant coarse source can later need thousands of fine batches before its atomic overlap group
retires, and an unfinished plan stays ordered around the old camera after a return trip.

Consumer 0.7.2 publishes each complete source immediately, including explicit empty-source markers.
Old ancestors remain fallback only in space without a completed finer source. The any-hit shader
rejects their hits using an immutable signed 3D ownership hash table, appended to the same GPU
buffer as the proxy section records. Camera, reflection, water and shadow rays share this test.
Ownership uses the represented face's inward offset, integer world-origin addition and all three
coordinates. Neither a partial multi-batch source nor a pending BLAS can claim ownership.

The existing overlap-group completion still retires the remaining ancestors. Cancellation can
start from a mixed parent/child selection; already published geometry and its index retain their
graphics timeline lifetime. A camera crossing an anchor cell cancels only unpublished work and
reorders the next plan around the current position. This does not shrink the requested horizon.

The first full return replay passed its structural/lifetime checks but failed visual review:
the returned view still contained large cherry/grass cubes after 20 seconds. Its plan captured
1,021 targets on the return tick, while the current provider subsequently exposed 1,782 targets.
Position and projection advance on different ticks; the initial plan could reflect the previous
view's intentional coarse selection. The consumer now checks at bounded intervals for newly
available nearby fine sources missing from its current plan, and cancels/replans only unpublished
work when found. Build priority uses actual camera coordinates, rather than a 256-block anchor's
corner. The earlier structural PASS is not treated as visual qualification.

The extended ownership replay confirmed 618 previously fine sources had actually coarsened; 303
recovered by 40 seconds and 3,902 nested ownership checks exercised real parent/child overlap.
That establishes progressive handoff, but the visual latency was still unacceptable. Horizontal
queue ordering put underground cells before the visible hillside. Voxy now orders 128-block quality
bands using full 3D distance to the actual camera. Nearby newly available fine sources also bypass
the eight-second idle refresh delay, as well as interrupting obsolete in-progress plans.

The final replay uses an isolated copy of the player's save/configuration, near distance 4,
2560x1600 output, FOV 74 and the 1,024-block LOD horizon. Full-resolution F3 inspection corrects
the thumbnail's ambiguous minus signs: the supplied yaws are -150.4 and -100.4, not the positive
angles used in the earlier scheduling experiments. The final route uses these actual poses. It
moves 640 blocks east, faces away for 200 seconds and requires proof that previously published
fine sources really became large coarse sources before returning. It records the approach after
5/20/40 seconds and requires real fine-source recovery and nested parent/child ownership checks.

The post-render probe compares the complete ownership upload with the actual published source set.
Raw coarse-batch counts now include intentional clipped fallback; they alone are not evidence of
visible giant blocks. Structural PASS markers from earlier experiments are not visual qualification.

Recently retired complete fine sources can also remain in a non-rendered GPU cache. Its default
ceiling is the smaller of 256 MiB and one eighth of the LOD budget; driver pressure disables it.
Sources enter only after their last graphics use, transfer whole-source ownership on reuse and
remain charged to the LOD memory budget. World/provider resets, shutdown and allocation pressure
invalidate pending cache admissions and release optional owners. An explicit empty replacement
does not resurrect old terrain. Cached detail is a temporary fallback while the latest source
revision rebuilds, not a substitute for processing current provider data.

The player's acceptance criterion is visible, continuing recovery, even if it takes some time.
The fix therefore prioritizes publishing completed nearby sources throughout the wait. It does
not promise instant refinement of the entire horizon or a fixed recovery deadline on all hardware.

The accepted 0.7.2 return replay (`runtime-072-cache.log`) rendered 8,318 frames with 308 near
ownership checks, 1,934,578 finer-source checks and 23,256 checks of actual nested fallback.
Of 624 fine sources present before leaving, 611 were demonstrably replaced by large coarse
sources while away. The return plan restored 620 complete cached sources (203 MiB), and 610 of
the 611 coarsened baseline sources were present again at the 40-second checkpoint. These counts
are source coverage, not a claim of pixel-perfect equality or complete horizon recovery.

Inspection of the matching 5/20/40-second captures confirms that the previously loaded hillside
and cherry trees recover promptly. Far-side coarse shelves and vertical terrain faces are still
visible within this capture window while thousands of remaining batches stream. The second
camera also retains coarser distant foliage. This checkpoint improves return continuity; it does
not qualify every far source as settled or claim that all visible giant geometry has disappeared.
The single missing baseline source also prevents claiming complete baseline recovery.

All 244 consumer tests and 32 shader compilations/validations pass. The release JAR matches the
395 runtime classes, all 32 SPIR-V modules and the mixin configuration used in this replay.
The strict Vulkan 1.4.363 log gate passes with live DLSS-RR/NGX clear synchronization exercised,
zero VUID/synchronization reports, zero allocation deferrals and no temporary distance cap.
Native LOD bytes return to zero at shutdown (981,670,704-byte peak); water field and bounds cache
also shut down at zero. These checks are not an FPS comparison or full player visual acceptance.
