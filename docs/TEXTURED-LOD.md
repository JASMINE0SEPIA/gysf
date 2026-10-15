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
hierarchy. Water retains the existing thin glossy LOD approximation. Its biome
colour uses the same normalized optical-tint convention as the near fluid
mesher, without albedo linearization or multiplication by the baked blue water
sprite. Shadow tint follows the same rule. Water sprite alpha does not remove
water geometry. An outward crossing uses the existing dielectric water handler
to exit a near/camera water medium, including its waves and Fresnel response.
Entering rays keep the glossy approximation: sparse LOD water cannot push a new
absorbing medium. This aligns tint inputs and permits water exits; transmission,
volume absorption and waves on entering far-water hits still differ from near
water, so it is not a complete near/far match.

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
