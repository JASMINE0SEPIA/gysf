# gysf and vooxxy

gysf is a Minecraft 26.3 Fabric Vulkan path-tracing fork of Caustica. vooxxy
provides persistent distant-terrain data, model baking and hierarchical mesh
selection. gysf owns the ray-traced scene and the final world image.

## Source baseline

- Caustica: `ComfyFluffy/Caustica`; project-owned code is LGPL-3.0-or-later.
- Minecraft 26.3 adaptation: `xysgottaken2/Caustica`, `port-26.3`,
  `96ef701e2b1e5283ea28a2ce158f5c0ffefd8a57`, with its complete history retained.
- External LOD provider reference: `skayla2007/voxy`,
  `0b61b96d1e4a495522ece3c1428e7e78b162c0d2`, API 5.
- Paired consumer reference: `skayla2007/Caustica`,
  `e83674e4d1c4ca02b6321de23cebaf7bb1cc90b6`.
- NVIDIA SDK: DLSS `v310.7.0`, source commit
  `a291cc7d2cc642a51566f3dfd5376f635cd1b284`; vendor licensing stays separate.

Original source packages, texture namespaces and the `caustica.toml` settings
namespace remain compatible. The Fabric mod identity and artifact name are
`gysf`; install one Caustica-derived renderer JAR at a time. The README stays
one sentence. Original development documentation is in CAUSTICA-26.3-BASE.md.

## Build

Use JDK 25, Gradle Wrapper 9.7.0 and Slang 2026.18. A Vulkan SDK supplies
`glslangValidator` and `spirv-val`. A separate Slang binary can be selected with
`-PslangcPath=<path>`; its version must support explicit std430 device pointers.
The local compiler ZIP SHA-256 is
`6ffa4827b519fd0a85b38407049d87ab0c1f045fe2289cb1e6831f965169f8a1`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
# Set VULKAN_SDK and DLSS_SDK to your own installations.
.\scripts\Build-26.3.ps1 -Native -SlangcPath 'C:\your\Slang-2026.18\bin\slangc.exe'
```

NGX is built from source using CMake/MSVC and the official NVIDIA SDK. Vendor
DLLs and downloaded SDKs stay outside Git. Without optional vendor inputs,
the corresponding DLSS/FSR/NRD/XeSS options are unavailable. See the original
THIRD_PARTY_NOTICES.md for their separate terms.

## Joint development run

Build the sibling `../voxy-vulkan-26.3` repository first, then run:

```powershell
.\scripts\Build-26.3.ps1 -Joint -ValidateVulkan
```

The development-only `gysf_smoke` mod creates or reopens its own fixed-seed
world under `run-smoke/`, moves the camera, records a screenshot, reloads
resources, visits the Nether and returns, verifies RT frames and resident LOD acceleration structures, and
saves/disconnects before stopping. It is never included in the production JAR.
The joint runtime does not install Sodium or read a PCL instance.

## Current integration boundary

gysf consumes immutable API 5 packed quads and model metadata through an optional
reflection boundary. Worker-owned snapshots retain float face geometry, UV
repetition, per-face atlas identity, conditional biome tint and fluid corner
heights. Ray and shadow traversal sample the provider's borrowed RGBA atlas,
including cutout coverage. Both use the existing RT LOD acceleration structures
and near-terrain readiness mask. See [TEXTURED-LOD.md](TEXTURED-LOD.md).
Voxy's progressive BLAS handoff uses 3D overlap groups and whole-source
readiness, including empty replacements. The DH input retains its separate
2D coverage policy.

`-Dgysf.voxy.colourProxy=true` retains the older average-colour input for controlled
comparisons. The textured path uses block-state material profiles, not full
per-sprite LabPBR normal/specular maps. gysf 0.4.0 routes API 5 water through the
near-field dielectric handler in both directions, sharing optical tint,
refraction, absorption, waves and guides. Wave-aware origins cover primary TIR
and reflected guide probes as well as split/secondary continuations. Near-ready
ownership is independent of ray direction, including sloped fluid triangles.
Legacy colour proxies retain a glossy entry approximation. Provider fluid-height
quantization and coarse geometry still limit geometric near/far continuity.

gysf 0.5.0 optionally consumes API 6 UNORM16 fluid corner heights while retaining
API 5 geometry fallback. The paired Vulkan.7 provider exports these values only
for external rendering and includes them in CPU cache payload accounting.
This addresses the 8/9-to-7/8 still-water loss without changing raster quad bits.
Provider Vulkan.8 also matches RT fluid heights and real-shape interface culling
under solid, glass and slab ceilings, and invalidates diagonal fluid dependencies
after edits. The isolated flow/shoreline/covered-water geometry fixture compares
actual near and exported RT vertices before and after adding/removing a ceiling.
Coarse voxel aggregation, resource-pack variants and detail selection still
need broader geometric acceptance.

gysf 0.6.0 records a geometric normal for each near-fluid triangle, matching the
Voxy packer on non-planar flows. Animated water adds its spectrum gradient to
the rest-plane slope rather than flattening it, and its bounded search scales
with the height slider and ray rate relative to that plane. The production
Slang host probes cover both orientations, strengths through 4, vertical/oblique
rays, distance footprints and rebasing. Displaced-water shoreline clipping and
temporal image fidelity remain separate from those numerical identities.

gysf 0.6.1 evaluates the actual previous wave phase for reflection normals and
vertical motion. The earlier first-order rewind exceeds the wave-height
envelope at high speeds / long frame intervals within the accepted history
window. Frozen or zero-height waves reuse the current field. The production
Slang temporal probe checks 12960 phase/position/slider/interval/footprint
combinations against direct previous-field evaluation; visual temporal
acceptance and long-distance anchor continuity still need separate checks.

The external provider starts with a 1024-block selection cap; the smoke run uses
512 blocks. `-Dvoxy.external.maxDistanceBlocks=<512..10000>` selects a development
limit without changing the user's stored Voxy distance. Per-sprite PBR, LOD emitter sampling,
reflection detail selection and a total-residency VRAM budget remain required
before production qualification. Geometry beyond visited/imported terrain cannot
be synthesized from the LOD cache.

gysf 0.3.0 adds process-budget telemetry and native LOD allocation admission,
including staging/scratch, compaction overlap and GPU retirement. Pressure can
reduce a temporary provider distance and recover gradually without changing
saved settings. See [GPU-MEMORY.md](GPU-MEMORY.md) for the measured scope and
remaining whole-engine/long-travel limits.

Progressive coverage, shader guides, shared Vulkan host synchronization,
resource reload, dimension change, clean shutdown and motion performance are
acceptance requirements. Automated CPU/ABI/SPIR-V checks are distinct from
GPU rendering and visual validation.

## Development order

1. Keep the current 26.3 Vulkan provider/RT prototype reproducible, including
   resource reload, dimension changes and optional-Sodium startup.
2. Validate the textured API 5 path across resource packs and material classes.
   Match sprite PBR and accepted near/far coverage; preserve Voxy's existing
   shape approximation rather than promising detailed vanilla stair geometry.
3. Share near-terrain readiness and LOD coverage across primary, shadow and
   reflection rays; add distant emitters and PBR material lookup.
4. Budget atlas, mesh and acceleration-structure residency together; measure
   camera-motion frame times and memory pressure before increasing distance.

The paired provider is [vooxxy](https://github.com/JASMINE0SEPIA/vooxxy), branch
`jasmine/26.3-vulkan`. Local GPU evidence and its limits are recorded in
[VALIDATION-2026-10-15.md](VALIDATION-2026-10-15.md).
