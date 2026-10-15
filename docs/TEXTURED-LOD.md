# API 5 textured LOD input

`CausticaBridge.pollScene` runs before RT frame command recording. gysf uses
reflection only at this optional mod boundary, so builds and standalone runtime
do not require Voxy classes. `VoxySceneReader` copies packed quads, model records
and colours into immutable CPU snapshots; workers never query a live world or
touch native provider resources.

`RtVoxyMesh` converts the provider's planar faces to float triangles. UVs repeat
per LOD voxel rather than stretching one image across a greedy quad. Face inset
and extent retain their fractional coordinates. Fluids decode the provider's
12-bit four-corner heights, including top vertices on side faces, and calculate
each triangle's actual normal. These are Voxy's shapes, including Voxy's own
model simplifications; this is not an independent vanilla model mesher.

Textured batches use the existing RT LOD section table, bounded BLAS scheduler,
compaction and per-section vanilla-ready mask. Every triangle is non-opaque to
Vulkan so primary, reflection and shadow rays can reject ready near terrain or
transparent texels. A 3D API 5 selection bypasses DH's X/Z-only coarse filter.
The readiness probe steps into the represented voxel along its packed face axis,
independently of ray direction. A water triangle's sloped shading normal does not
move that probe into lateral neighbours. The mask and hit position both use the
current terrain rebase.

Progressive publication also treats Voxy sections as aligned 3D cubes. An old
source and all intersecting replacement sources form one handoff group. Every
BLAS batch in that group must finish before a single checkpoint retires the old
sources and activates their replacements. This applies to refinement,
coarsening and same-key revision updates; different heights and unrelated
branches can still publish independently. Empty selected leaves carry explicit
completion markers so removed terrain does not leave an old solid fallback.
Unpublished replacement resources remain session-owned across checkpoints and
are destroyed if the session is cancelled. Complete Voxy sources in an
intermediate checkpoint remain reusable after cancellation.

`-Dgysf.voxy.validateCoverage=true` checks every published Voxy partition for
overlapping cubes and mixed source revisions. Ten transition tests cover
vertical separation, negative coordinates, eight-child replacement,
coarsening, empty markers, multi-batch updates and 100 shuffled completion
orders with constant covered volume. These prove publication invariants,
not identical silhouettes at different geometric LODs.

The provider retains ownership of its atlas. Borrowed descriptors are keyed by
an epoch-specific owner, preventing Vulkan handle reuse from reviving a stale
descriptor. They are append-only within a pipeline lifetime and cleared when
that pipeline is rebuilt. The current atlas slot travels in the reflected
`WorldPush` ABI, so descriptor changes do not require triangle-buffer patching.
Atlas upload barriers include ray-tracing reads. Sampling derives the actual
tile size from image dimensions, clamps each face at the chosen mip and handles
the provider's reduced-resolution mip-tail atlas without fixed pixel offsets.

The shader samples per-face albedo/alpha and applies the model's conditional
biome tint in linear lighting. Coverage uses base-level alpha so mip averaging
cannot fill foliage holes; closest-hit albedo still uses ray-cone mip filtering.
It preserves water/glass/emission classification
and block-state material profiles. The baked RGBA atlas does not carry LabPBR
normal/specular pages, and distant emitters do not yet enter the light-sampling
hierarchy. API 5 water uses the same dielectric handler as near water. Its biome
colour uses the same normalized optical-tint convention as the near fluid
mesher, without albedo linearization or multiplication by the baked blue water
sprite. Shadow tint follows the same rule. Water sprite alpha does not remove
water geometry. Entering and exiting crossings share Fresnel, refraction, medium
absorption, waves, caustics and denoiser guides with the near fluid path. Primary
TIR, split continuations, secondary rays and reflected guide probes all keep
their origins off both the waved surface and its undeformed BLAS plane. Guide
hit positions use the actual biased probe origin. Legacy DH/colour proxies
retain their glossy entry approximation. API 5 fluid corner quantization and
coarse cell geometry still limit geometric continuity; shader equality does not
recover missing or lower-resolution world data.
Fluid corners use three bits for heights 1/8..1 of a LOD voxel. A still-water
height of 8/9 therefore becomes 7/8; its world-space error grows with voxel
scale. More precise heights and refraction-aware detail selection are separate
geometry work, not something to conceal with a different water shader.

With API 6, the optional `fluidHeights()` sidecar replaces only the RT consumer's
corner values with four UNORM16 lanes in `(x<<1)|z` order. The unchanged API 5
quad bits still provide fallback when that method or a quad's precision data is
absent. The reader validates array alignment and copies the data into owned
snapshots; workers do not query the provider. Original fractional face geometry,
UVs, normals and near-ready face ownership retain their existing roles.
`-Dgysf.voxy.legacyFluidHeights=true` selects the old geometry at startup for
controls. This removes most corner quantization loss but cannot reconstruct
detail erased by coarse source voxels or unvisited terrain.

The decoding and atlas-layout reference is `skayla2007/Caustica` at
`e83674e4d1c4ca02b6321de23cebaf7bb1cc90b6` (`RtVoxyMesh`, `voxy.slang`), adapted
to the 26.3 renderer and existing scheduler. Fluid packing follows vooxxy's
`quad_util.glsl` and `RenderDataFactory`.

Eleven CPU tests check winding, depth boundaries, greedy UV scale, fluid top/side
heights, rebasing, material identity, bucket order, snapshot ownership, water
optical tint versus solid albedo, and sloped-water face identity.
The provider retains leaf alpha-cutout metadata when an external renderer owns
the world, instead of applying standalone Voxy's opaque foliage override.
`-Dgysf.smoke.textureFixture=true` builds a leaf/plank/brick/glass/slab fixture
inside the isolated smoke world; it never edits a PCL world. The smoke harness
also requires nonzero textured batch count and a bound atlas after its dimension
round trip. Adding `-Dgysf.smoke.visualOnly=true` instead performs a shorter
30-degree-FOV visual check, records whether the fixture's near RT section is
ready, and captures the same camera with the textured LOD hidden. It restores
the atlas before saving and exit. The developer flag `gysf.voxy.hideTextured`
affects only these textured LOD hits and does not disable native near terrain.
`-Dgysf.smoke.visualControl=true` records the same comparison during the full
resource-reload/dimension lifecycle test instead of shortening it.
`-Dgysf.smoke.motion=true` extends the full run to 2900 world ticks, traverses
positive/negative X at changing heights, teleports to another quadrant and
returns. It requires observed replacement swaps and coverage checks; use it
with `gysf.voxy.validateCoverage=true`.
`-Dgysf.smoke.waterFixture=true` adds a quartz-bottomed pool across section
boundaries, verifies that it spans ready near terrain and unready far terrain,
records above-water and underwater views, and requires converted LOD water.
Use it with the full run; `visualOnly` exits before the underwater view.
GPU and visual observations belong in the validation record.

`-Dgysf.smoke.waterMatrix=true` records frozen-wave underwater controls for hidden
LOD, flat water, 8 bounces, and raw 8/2 bounces in one isolated process. This
shorter 1720-tick diagnostic skips dimensions and travel. Adding
`-Dgysf.smoke.waterNearReference=true` requests a 12-chunk near window and requires
the far pool sample to be near-ready. The normal 4-chunk run requires that same
sample to remain LOD-owned. `-PsmokeMaxBounces=4` selects the product-default
bounce cap for reference comparisons; the ordinary smoke default remains 2.
`-Dgysf.smoke.staticWaterFixture=true` freezes the fixture phase through its
above/below-water captures, then restores animation before the full motion route.
The water camera is (64.5,140,0.5), yaw 0, pitch 16, FOV 30 above water and
(64.5,110,32.5), yaw 0, pitch -2, FOV 70 below water.
`-Dgysf.smoke.waterCompareOnly=true` freezes both captures and finishes at tick
1290 without resource reload, dimensions or motion. This separates water
comparison from post-reload scene repopulation. The full-near reference requires
all 129 centre-line samples to be near-ready, while the mixed run records the
near-ready count and requires its far sample to remain LOD-owned.

`scripts/Test-WaterOrigins.ps1` executes the production Slang origin function
through the host backend, checking 2080 displaced/rest-plane cases and a
total-internal-reflection self-hit negative control. It is separate from the
Java/shader/ABI/JAR checks and the Vulkan scene tests.
`-Dgysf.smoke.requirePreciseFluids=true` also requires converted API 6 fluid quads
and verifies the actual RT vertices for a known level-zero still-water cell.
`-PvooxxySmokeJar=<path>` selects a pinned provider JAR for API 5 compatibility
checks instead of the newest sibling build.
`-Dgysf.smoke.expectLegacyFluids=true` requires the precise-fluid conversion
counter to remain zero in that compatibility run.

`-Dgysf.smoke.fluidTopology=true` adds frozen flow/shoreline and glass/solid/slab
ceiling fixtures at Y=127. It compares actual near-fluid tessellation with
exported level-zero RT vertices and face presence; all fixture states must be
received by the client before comparison. Use it with `waterCompareOnly` to
separate geometry acceptance from scene reload. The diagnostic-only flag
`gysf.smoke.topologyAllowMismatch=true` logs differences instead of failing;
such a runtime PASS is not topology acceptance.
`-Dgysf.smoke.fluidTopologyEdits=true` also changes a ceiling across X/Y/Z provider
boundaries and repeats the comparison after both addition and removal. This
1550-tick mode keeps its isolated world frozen until tick 1540 and explicitly
skips dimension changes. It requires the topology comparison mode and tests
dependency invalidation rather than just initial construction.

The topology probe also captures the real private near-fluid primitive records
through smoke-only reflection, then checks their triangle normals against the
matching exported geometry. This adds no test API to the mod and does not
increment RT conversion counters. `scripts/Test-WaterSurfaces.ps1` executes the
production Slang wave evaluator against zero-amplitude, finite-difference normal,
surface-height residual and world-anchor controls. It is independent of the
origin probe and of GPU/temporal image acceptance.
`-Dgysf.smoke.waveStrength=<0..4>` explicitly sets and verifies the isolated
run's wave-height multiplier. It enables waves and keeps speed 1 for an animated
full run; the comparison mode still freezes its wave phase for captures.
`-Dgysf.smoke.waveSpeed=<0..4>` selects and verifies a different animated speed;
comparison captures remain frozen and full runs resume the requested speed.
`scripts/Test-WaterTemporal.ps1` compares the production temporal field with an
independent evaluation at the previous frame's phase. Its 12960 cases cover
height/speed 0, 1 and 4, six frame intervals through 0.25 s, five clock phases,
16 positions and three footprints. This tests guide mathematics, not temporal
image quality or the cost of tracing the complete scene.

`scripts/Test-WaterDomain.ps1` feeds production Java phase transport into the
production Slang host consumer. Its 15552 paired field comparisons cover signed
origins through the world border, spatial rebases, eight-second time-base
changes, hourly boundaries and one-week timestamps. `-Legacy` requires the old
4096-coordinate / hourly-time policy to fail as a negative control. It is not a
GPU output readback test. `-Dgysf.smoke.waterDomainMotion=true` extends the full
motion route across positive/negative 4096-block regions and asserts observed
terrain origins, not just issued teleport commands. Use it with `motion=true`.

`-Dgysf.smoke.cleanWater=true` uses the separate `gysf-clean-water-26.3` save,
forbids topology/material fixtures, verifies air above the complete pool through
Y=143, freezes the isolated server at tick 850, disables clouds and uses manual
EV 0. Clean `waterMatrix` skips resource reload and keeps forced chunks until
its final captures; it is an image control, not the lifecycle run.
Use `waterNearReference=true` for the 129/129 near-ready pool-lane reference.
`cleanWater`, `waterCompareOnly` and `waterSequence` together sample animated
underwater images at client ticks 1320..1560, skipping repeated render-frame
identifiers. The CSV records request time, camera, wave tuning and ownership.
Screenshot readback/PNG encoding affects throughput; this is not a performance
benchmark or a capture of every rendered frame.
`scripts/Analyze-WaterCaptures.py <directory> --sequence --output <json>` checks
metadata and describes fixed PNG regions. Add `--near-reference` for the near
run and `--video <mp4>` for an FFmpeg convenience preview. Raw PNG/CSV files are
the measurement sources; previews are lossy and are not timing evidence.

`scripts/Test-WaterGrazing.ps1` is a deliberately separate diagnostic of an
unresolved production limitation: the current three-step solver fails its
0.001-block residual gate. Its non-zero exit must not be counted as passing
acceptance. `-Prototype` evaluates a shaderTest-only conservative solver; its
residual/limited nearest-root checks do not qualify it for production or prove
its GPU cost. See the validation record before interpreting either result.
