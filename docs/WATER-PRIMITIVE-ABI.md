# Water primitive packet: development 0.7.0

`RtWaterGeometry` is the shared CPU encoder intended for near terrain and
external LOD water. `water_primitive.slang` decodes its exact packet and calls
the finite-water solver now shared from `shaders/world/water_patch.slang`.
The development pipeline can select them with `-Dgysf.rt.finiteWater=true`.
The gate remains off by default while shoreline and camera-medium work continues;
see [the runtime integration record](WATER-RUNTIME-2026-10-15.md). The installed
0.6.2/Vulkan.8 pair retains its previous behavior and hashes.

## Packet and geometry rules

Each primitive is five std430 float4 lanes (80 bytes):

| Byte offset | Values | Meaning |
| --- | --- | --- |
| 0 | v0.xyz, freeTop | Triangle vertex 0; tag 0: static face, 1: constant source layer, 2: varying source height |
| 16 | v1.xyz, shape | Triangle vertex 1; projected domain 0..3 |
| 32 | v2.xyz, source | Triangle vertex 2; tag 1: local source layer, tag 2: missing fourth-corner rest Y; ignored for tag 0 |
| 48 | minX, minZ, width, depth | Bounds of the original full quad |
| 64 | w00, w01, w10, w11 | Shared shoreline envelope in [0,1] |

The shape codes are 0: u>=v, 1: u<=v, 2: u+v<=1, 3: u+v>=1. Both diagonals
are supported. Rest normals derive separately from each triangle's winding,
so a nonplanar quad preserves its actual two planes. Flat side/bottom faces
use a two-sided triangle intersection; disabling animation also retains the
exact rest triangles. A flat, dry top remains a finite triangle rather than
an infinite plane.

Both halves of a free top select the same source mode. If all four corners
select one integer water layer, tag 1 retains that layer and the existing
same-owner field lookup. Otherwise tag 2 reuses the source lane for the
missing fourth-corner height; each triangle reconstructs the same full
bilinear quad height domain. Each canonical field node selects its source
layer from that domain, including in the previous-frame shading lookup.
This domain selects field data only; the actual rest surface still consists
of the original two triangles. There is no extra primitive allocation.

Source-layer selection uses floor(height), choosing the layer below when
height is within 0.001 blocks above an integer. This covers L0-L4 UNORM16
height quantization and FP32 rebasing at full-cell ties. It does not snap
vertices, change the displacement bound, or reduce waves. CPU field-owner
requests cover every layer needed by the domain; the shader skips a uniform
tile region only when its source layer is constant throughout that region.
See [the source-layer repair and evidence](WATER-LAYERS-2026-10-16.md).

The separate AS build input is one Vulkan AABB per triangle: six floats
(minXYZ, maxXYZ), 24 bytes. Only upward-facing free-top triangles grow by the
2.2-block maximum displacement. Padding uses `nextDown`/`nextUp` as well as
an epsilon, so it survives FP32 spacing at large local coordinates. Vertices
and rectangles remain section-local; shader callers provide a translation
into the same rebased world space used by the transported wave domains.
General affine/negative-scale instance transforms are outside this packet's
current contract.

The encoder takes the existing indexed water-bucket range, including a
nonzero triangle base. It rejects incomplete pairs, invalid indices,
degenerate triangles, nonfinite positions, invalid envelopes and nonrectangular
free-top projections before native allocation. The nullable envelope argument
currently means fully wet. It does not supply the missing world-space shoreline
policy and must not be treated as a finished near/far transition.

## Verification and current limit

The current complete Gradle check passes 214 Java tests. Encoder coverage
includes indexed order, bucket offsets, either diagonal, separate
nonplanar planes, static faces, malformed inputs and FP32-safe padding. An
analytic sum of the production spectrum's maximum positive amplitudes at the
actual maximum shader slider verifies that 2.2 blocks encloses the wave field.
Source-domain tests additionally cover both triangle reconstructions, signed
anchors, per-owner layer coverage against independent node enumeration,
full-cell quantization, and the CPU/shader layer-selection constant.

`WaterGpuGeometry` exports eight triangles with the production encoder. The
native RT harness now uploads those 80-byte records and builds its AABBs from
the encoder's 24-byte bounds, replacing its hard-coded geometry reconstruction.
The CPU dense-reference probe can load the identical exported packet with
`Test-WaterPatch.ps1 -Geometry <primitives.txt>`.

| CPU reference mode on the exported packet | Hits / misses | Failures / exhausted | Max distance error |
| --- | ---: | ---: | ---: |
| Selected triangle | 1346 / 2750 | 0 / 0 | 0.0000214577 blocks |
| All eight triangles | 1426 / 2670 | 0 / 0 | 0.0000345707 blocks |

All 21 independent clipping checks pass, including four additional alternate-
diagonal checks. Ten flat-primitive checks pass for top/bottom directions,
finite bounds, parallel rays, side faces, dry envelopes and animation-off
behavior. Gradient, envelope, curvature and continuity properties also pass.

The original packet integration measurements below predate the varying-height
source-layer repair and remain historical evidence for that earlier packet.
At that checkpoint the native RT executable compiled and all five SPIR-V modules passed
Vulkan 1.2 validation. After the user exited Minecraft, the packet-backed GPU
runs completed on the RTX 5060 Laptop/616.92 with 262144 rays per dispatch.
Both coherent/shuffled layouts have zero hit/miss/distance failures in the
4096 distinct readbacks, and zero Vulkan synchronization validation errors.
Selected-triangle medians are 0.319008/0.363984 ms (maximum distance error
0.00002218 blocks); all-eight-triangle medians are 0.396672/0.561792 ms
(maximum error 0.0000372 blocks). These are synthetic traversal measurements,
not Minecraft frame times. The previous measurements in WATER-PATCH-INTEGRATION.md
remain separate records of the older, hard-coded geometry harness.

```powershell
# While Minecraft is running: compiler, encoder export and CPU checks only.
.\scripts\Test-WaterGpu.ps1 -Trace -CompileOnly -OutputDir artifacts/packet-compile
.\scripts\Test-WaterPatch.ps1 -Geometry artifacts/packet-compile/primitives.txt -RayTable artifacts/packet-rays.csv
.\scripts\Test-WaterPatch.ps1 -Scene -Geometry artifacts/packet-compile/primitives.txt -RayTable artifacts/scene-rays.csv

# After Minecraft exits, use fresh directories for actual GPU measurements.
.\scripts\Test-WaterGpu.ps1 -Trace -RayCount 262144 -OutputDir artifacts/packet-trace
.\scripts\Test-WaterGpu.ps1 -Trace -Scene -RayCount 262144 -OutputDir artifacts/packet-scene
python scripts/Analyze-WaterGpu.py artifacts/packet-rays.csv artifacts/packet-trace --finite-patch
python scripts/Analyze-WaterGpu.py artifacts/scene-rays.csv artifacts/packet-scene --finite-patch
```

The gated game path now supplies terrain/LOD upload, retirement, separate
homogeneous triangle/AABB BLAS instances, SBT routing, intersection-stage
visibility and finite-hit optical/guide data. Shared shoreline data,
camera-medium handling and the remaining game qualification still follow
WATER-PATCH-INTEGRATION.md. Default selection remains disabled until
these requirements are verified. Development builds use the
separate 0.7.0 version; they are not the installed playable 0.6.2 artifact.
