# Finite water patches: measured prototype and runtime boundary

The installed renderer remains gysf 0.6.2 with vooxxy Vulkan.8. None of the
code in `src/shaderTest/` is imported by production shaders or bundled in the
JAR. This work establishes a candidate for replacing the rest-plane water
proxy; it does **not** repair that proxy in the installed renderer yet.

## Why a different acceleration primitive is needed

The current triangle BLAS contains the fluid's rest surface. Raygen refines
that hit against an infinite wave field. Improving the numerical solver alone
does not make this representation correct: the first wave crossing can be
outside the original fluid triangle, and a horizontal ray or an origin inside
a crest can cross the displaced surface without crossing any rest triangle.
The expanded grazing diagnostic still fails on the production evaluator.

The prototype instead gives each free-surface triangle a conservative AABB,
clips the ray to its finite XZ triangle and displaced vertical envelope, then
finds the first surface crossing inside that interval. The AABB is static for
the maximum supported wave-height slider; time, wave detail and height changes
do not require rebuilding it. Side and bottom faces require their own static
surface treatment; a horizontal face is not automatically a free top surface.

`WaterPatch` retains the actual affine rest plane of one triangle, the full
quad's bounds, its half index and four shared shoreline amplitude weights.
Both halves can have different normals, as flowing-water quads do. The wave
amplitude envelope uses a tensor product of cubic smoothsteps. Shared corner
weights give shared values and first derivatives across unit-grid edges; an
all-zero shore edge has zero wave displacement and zero additional wave slope.
Its gradient includes `waveHeight * envelopeGradient`, not just a scaled wave
normal. Its directional curvature bound includes the product's cross terms.

This is a mathematical envelope, not a finished world-space shoreline policy.
Different LOD cell sizes with independently interpolated weights need not give
the same field. An API consisting only of four coarse corners would not by
itself establish near/far continuity. Runtime integration must preserve a
shared attenuation field or an explicitly checked transition representation.

## CPU and GPU evidence

The 4096 finite-patch rays cover strengths 1/4, eight phases, angles
0/0.5/1/2/4/8/30/90 degrees, both directions, footprints 0/0.5/2/8,
flat/sloped/nonplanar rest geometry, fully wet/dry/shore/saddle envelopes and
both triangle halves. The nonplanar quad has two distinct rest normals and
equal heights on the shared diagonal. Dense 0.005/0.0025-block searches with
bisection agree on hit/miss and first-root distance within 0.001 blocks.

There are two reference modes: a selected triangle, and the nearest surface
among all eight fixture triangles. The latter does not use instance masks to
isolate a target. CPU results are:

| Reference mode | Hits / misses | Failures / exhausted walks | Maximum ray-distance error |
| --- | ---: | ---: | ---: |
| Selected triangle | 1346 / 2750 | 0 / 0 | 0.0000207424 blocks |
| All eight triangles | 1426 / 2670 | 0 / 0 | 0.0000345707 blocks |

Seventeen hand-computed clipping checks are independent of the dense solver.
They exposed and fixed two failures in the original prototype's fixed
parallel-rate epsilon: a small nonzero derivative can still produce a valid
crossing on a long ray. All 17 now pass. The 4096 finite-difference gradient,
sampled height-envelope and directional-curvature checks pass, as do shared-edge,
dry-edge and nonplanar-diagonal continuity checks. Maximum gradient difference
is 0.000386719. These sampled checks do not constitute a proof for all tangencies.

The native Vulkan compute and procedural-RT runners use the actual Java-produced
FP32 wave-domain table. Every SPIR-V module passes `spirv-val --target-env
vulkan1.2`. Both runners require Khronos validation with synchronization checking.
RT uses real BLAS/TLAS AABBs, an intersection shader, a procedural hit group,
closest-hit and miss stages. The intersection shader selects geometry using
the instance's custom index, independently of the input ray's target fixture.

The final runs use an RTX 5060 Laptop GPU, driver 616.92, 262144 rays per
dispatch, six alternating warm-up dispatches and twenty samples per variant
in alternating AB/BA order. Each layout contains 4096 different cases repeated
64 times; readback and CSV writing are outside the timestamp interval.

| Candidate kernel / scene | Coherent median | Shuffled median | Maximum distance error vs CPU | Hit/miss or distance failures |
| --- | ---: | ---: | ---: | ---: |
| Finite-patch compute | 0.246672 ms | 0.273632 ms | 0.000193600 blocks | 0 |
| RT, selected triangle | 0.301968 ms | 0.343376 ms | 0.000023600 blocks | 0 |
| RT, all eight triangles | 0.378160 ms | 0.528736 ms | 0.000037200 blocks | 0 |

Vulkan validation errors are zero in all three final runs. RT's maximum
reported-hit residual is 0.000001015741 blocks. Miss work/exhaustion is not
instrumented in the RT payload: the CPU reference checks remain mandatory.
The diagnostic rest-plane/Newton control fails 1191 selected-triangle and
1236 whole-scene cases. It deliberately retains the old field and has no shore
envelope, so these counts diagnose the representation as a whole; they do not
isolate the numerical solver's contribution.

The expanded **unbounded** grazing candidate also passes all 4096 dense
first-root references: CPU maximum distance error 0.000510216 blocks, GPU
maximum 0.005554 blocks. Its final 262144-ray compute medians are
0.576400 / 1.201952 ms. This corpus differs from the finite-patch corpus, so
those timings are not a performance ratio between the two algorithms.

All timings are synthetic kernel/fixture observations, not Minecraft frame
times or a whole-world throughput guarantee. Real water can touch many AABBs,
uses multiple ray types and optical continuations, and competes for cache,
memory and denoising resources. Current evidence does not cover arbitrary
large/coarse patches, translations, negative-scale instances, exact tangency,
resource churn or scene-wide VRAM pressure.

## Reproduction and retained evidence

Use JDK 25, the pinned Slang 2026.18 compiler, Vulkan SDK and MSVC/CMake.
Run only one GPU workload at a time. All GPU commands require a fresh output
directory; the scripts refuse to overwrite an existing `results.json`.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
$env:VULKAN_SDK='C:\VulkanSDK\1.4.341.1'
.\scripts\Test-WaterPatch.ps1 -RayTable artifacts/patch-rays.csv
.\scripts\Test-WaterPatch.ps1 -Scene -RayTable artifacts/scene-rays.csv
.\scripts\Test-WaterGpu.ps1 -FinitePatch -RayCount 262144 -OutputDir artifacts/patch-compute
.\scripts\Test-WaterGpu.ps1 -Trace -RayCount 262144 -OutputDir artifacts/patch-trace
.\scripts\Test-WaterGpu.ps1 -Trace -Scene -RayCount 262144 -OutputDir artifacts/scene-trace
python scripts/Analyze-WaterGpu.py artifacts/patch-rays.csv artifacts/patch-compute --finite-patch
python scripts/Analyze-WaterGpu.py artifacts/patch-rays.csv artifacts/patch-trace --finite-patch
python scripts/Analyze-WaterGpu.py artifacts/scene-rays.csv artifacts/scene-trace --finite-patch
```

`artifacts/2026-10-15/water-gpu/` retains source snapshots, SPIR-V, production
domain tables, raw timing samples, CSV readbacks, reference tables, logs and a
SHA-256 inventory. `evidence.json` identifies the final profiles separately
from exploratory/failed attempts. The initial RT prototype had eight missing
AS-backing-buffer BDA usage flags; validation caught these, and the final
rebuilds include the flags. The record retains that failed attempt.

## Required game integration

1. Export actual free-top triangle geometry and a common shoreline field from
   `RtFluidMesher` / `RtTerrainMesher` and provider `ExternalFluidSurface`.
   Covered/waterlogged/flowing cells and stale-neighbour invalidation must agree
   on both sides. Four interpolated coarse weights alone are insufficient.
2. Add AABB geometry ownership, storage and accounting to `RtAccel`,
   `RtSectionBuilder`, terrain residency and the external LOD path. Retain
   current retirement/fence/budget guarantees and static side/bottom faces.
3. Extend `RtPipeline` hit groups, SBT routing, push-constant stage visibility
   and data layout for an intersection shader. Radiance/shadow/guide rays and
   the existing LOD ownership mask must use the same finite surface.
4. Supply actual displaced position, sidedness, normals and prior-frame field
   to optics and guides. Remove rest-plane-origin compensation only for the
   new primitives. Camera-medium detection must account for wave crests.
5. Run static and animated near/full-near/LOD controls, grazing/shore/flow/
   covered-water fixtures, edits/reloads/dimensions and real memory/frame-time
   qualification. Only then rebuild and replace the playable JARs.

Ownership must still identify the represented fluid cell. Currently
`world.rahit.slang` queries the rest-triangle hit minus the outward ownership
normal times 0.01. A future procedural hit reports the displaced wave position;
that position can cross a 16-block section boundary into confirmed-empty air.
The small normal offset would then query the wrong section. Preserve a separate
rest-surface ownership position, derived from the primitive's affine rest plane
at the reported XZ (or explicit source provenance), independently of displaced
optical position and wave shading normal. This is an integration requirement,
not evidence that the current rest-triangle path has this new failure. Include
crests and troughs crossing a section boundary, with independently ready water
and air sections, in the near/LOD primary and shadow controls.

The PCL deployment is a separate, explicitly requested action: the known
0.6.2/Vulkan.8 pair was hash-verified and copied into the renamed `26.3`
instance. The prior Voxy/Sodium/Sulkan JARs were backed up, 14 unrelated mods
and 47 existing profile/config files were hash-verified unchanged, and no save
was opened. These new prototypes have not been installed there.

The subsequent [0.7.0 packet foundation](WATER-PRIMITIVE-ABI.md) moves the
finite solver into a shared shader module and introduces an actual CPU encoder
and decoder for near/LOD water. Its packet-backed GPU upload path subsequently
passes selected-triangle and whole-scene reference checks with clean Vulkan
validation. Keep those measurements separate from the older harness above.
