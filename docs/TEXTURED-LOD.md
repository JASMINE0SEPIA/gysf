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

The provider retains ownership of its atlas. Borrowed descriptors are keyed by
an epoch-specific owner, preventing Vulkan handle reuse from reviving a stale
descriptor. They are append-only within a pipeline lifetime and cleared when
that pipeline is rebuilt. The current atlas slot travels in the reflected
`WorldPush` ABI, so descriptor changes do not require triangle-buffer patching.
Atlas upload barriers include ray-tracing reads. Sampling derives the actual
tile size from image dimensions, clamps each face at the chosen mip and handles
the provider's reduced-resolution mip-tail atlas without fixed pixel offsets.

The shader samples per-face albedo/alpha and applies the model's conditional
biome tint in linear lighting. It preserves water/glass/emission classification
and block-state material profiles. The baked RGBA atlas does not carry LabPBR
normal/specular pages, and distant emitters do not yet enter the light-sampling
hierarchy. Water retains the existing thin glossy LOD approximation.

The decoding and atlas-layout reference is `skayla2007/Caustica` at
`e83674e4d1c4ca02b6321de23cebaf7bb1cc90b6` (`RtVoxyMesh`, `voxy.slang`), adapted
to the 26.3 renderer and existing scheduler. Fluid packing follows vooxxy's
`quad_util.glsl` and `RenderDataFactory`.

Nine CPU tests check winding, depth boundaries, greedy UV scale, fluid top/side
heights, rebasing, material identity, bucket order and snapshot ownership.
`-Dgysf.smoke.textureFixture=true` builds a leaf/plank/brick/glass/slab fixture
inside the isolated smoke world; it never edits a PCL world. The smoke harness
also requires nonzero textured batch count and a bound atlas after its dimension
round trip. GPU and visual observations belong in the validation record.
