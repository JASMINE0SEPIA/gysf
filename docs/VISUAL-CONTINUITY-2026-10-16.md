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
