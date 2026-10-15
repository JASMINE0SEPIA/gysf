# Finite water in the game pipeline

Development 0.7 supports `-Dgysf.rt.finiteWater=true` in the isolated client.
The switch is off by default. This is an integration checkpoint, not completed
near/far-water or underwater acceptance, and it has not replaced the PCL JAR.

## Representation and ownership

Vulkan requires homogeneous geometry types in each bottom-level acceleration
structure ([VkAccelerationStructureBuildGeometryInfoKHR](https://docs.vulkan.org/refpages/latest/refpages/source/VkAccelerationStructureBuildGeometryInfoKHR.html),
VUID-type-03792). A terrain section therefore keeps a triangle BLAS for material
buckets 0..2 and a separate all-AABB water BLAS. Empty AABB geometries 0..2 keep
water at geometry index 3, preserving the existing radiance/shadow SBT layout.
No real triangle is routed to a procedural hit group.

One logical section expands into two physical TLAS records when water is present.
Both records retain the same custom section index, transform, mask and SBT base.
Their publication, cancellation, graphics retirement and LOD accounting follow
the same section generation. A compact-copy of the triangle BLAS retains the
already built water child. AABB build inputs retire after the build; 80-byte
primitive records remain resident for intersection and hit shading.

The section table now has a 40-byte std430 stride: two u64 pointers, four u32
bucket bases, then a u64 water pointer. Slang reflection generates `SectionData`
and its serializer for both near and LOD writers. The native Vulkan TLAS ABI
remains 64 bytes per physical instance. CPU tests inspect real native records,
including a nonzero destination offset and the unchanged DH index bits.

## Hit, optics and guides

`world_water.rint.slang` calls the same finite primitive solver as the native
fixture. Camera, continuation, guide and shadow rays intersect the same full
spectrum, with footprint zero; shading filters do not redefine the geometry.
Static side/bottom faces and disabled waves retain their exact flat triangles.
Push constants include intersection-stage visibility.

Finite water reports the displaced hit directly. Closest-hit derives current
and previous normals and vertical displacement from the same primitive and
wave-domain frame. A dedicated flag prevents the old raygen Newton solve from
moving that hit again. Optical origins bias the actual surface rather than
projecting back onto a rest triangle. The payload adds one uint for the previous
normal, encoded as octahedral SNORM16x2; existing material lanes retain their
meaning. Previous normals first round through the same half3 storage as current
normals, so a stationary surface does not acquire a false temporal normal delta.

LOD suppression uses a separate ownership position on the primitive's rest plane
at the reported XZ. A crest crossing a 16-block Y boundary therefore does not
query a confirmed-empty air section instead of its source fluid section.

## Validation and remaining work

The shared Slang C++ probe retains zero failures/exhaustions for the 4,096-ray
whole-fixture first-root corpus, 21 analytic clipping cases and ten flat cases.
It additionally passes 4,257 runtime checks: full-sphere normal encode/decode,
static half-storage/history agreement, translated crest/trough ownership and
previous-field guide motion/normals. The maximum normal round-trip vector error
is 0.0000616252. These are CPU execution
of the shared shader functions, not GPU image or temporal acceptance.

The first game validation found a real size-query defect: the new water query
omitted an explicit geometry count, so a 640-byte AS and 1,280-byte scratch were
too small for a populated water build. Vulkan reported VUIDs 10126/12258 and the
isolated process lost its device. The failed log/crash report remain under
`artifacts/2026-10-15/water-runtime/initial-as-query-failure/`. Query and build
now share one constructor with an explicit four-geometry count and identical
flags. A native-struct CPU regression checks that constructor independently of
GPU execution. Failed attempts are not accepted runtime evidence.

After that correction, `water-runtime-lifecycle-validation2.log` completed with
188 Java tests passing beforehand and Vulkan/synchronization validation active.
The run rendered 19,046 RT frames, resource reload, a Nether round trip, and the
scripted terrain-origin route from -4,352 to 4,416 blocks. Separate observed peaks
were 130 near water children, 878 LOD water children and 990 packed physical water
instances in one TLAS. This verifies publication of actual native children, not
only encoding packets on the CPU. One-MiB injected LOD pressure produced one
deferral and distance reduction, two recoveries, and 199 ticks of published
fallback; recovery completed before reload. Native LOD peak was 705,945,216 bytes
and live bytes were zero at exit. No Vulkan validation error or synchronization
hazard was found in this corrected run.

Those lifecycle/counter results do not establish equal near/LOD optical pixels,
an isolated intersection cost, normal-encoding accuracy on GPU, or long-travel
stability. The screenshots are retained with the log under the ignored
`artifacts/2026-10-15/water-runtime/enabled-lifecycle/` directory. The installed
PCL mod pair has not changed. The previous memory/DLSS checkpoint `8d85a87` also
completed all four cloud CI jobs; that does not qualify the later water changes.

The default-off compatibility lifecycle also passed: 21,479 RT frames, reload
and Nether round trip, no memory deferrals, native LOD peak 637,574,576 bytes and
zero live bytes at exit. Its log and screenshots are retained in
`artifacts/2026-10-15/water-runtime/disabled-lifecycle/`.

After the final SNORM/history change, the static clean-pool control run passed
with 8,810 RT frames, 118 near water children, 460 LOD water children and a peak
579 physical water instances. Native LOD peak was 346,487,936 bytes and live
bytes were zero at exit; no Vulkan validation errors or synchronization hazards
were found. Waves, flat water, hidden textured LOD, bounce limits and denoising
controls are retained in `artifacts/2026-10-15/water-runtime/static-water-matrix/`.
The captures have no camera-state readback or input lock, so they are exploratory
image evidence, not a strict equal-scene pixel or performance comparison. Visible
far-boundary differences remain unresolved. All 188 Java tests, shader/ABI
checks and JAR validation passed before this run.

To reproduce the gated lifecycle in `run-smoke` after building the paired provider:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
$env:VULKAN_SDK='C:\VulkanSDK\1.4.341.1'
$env:DLSS_SDK=(Resolve-Path '.tools/DLSS-310.9.1').Path
$env:JAVA_TOOL_OPTIONS='-Dgysf.rt.finiteWater=true -Dcaustica.rt.omm=false -Dgysf.smoke.textureFixture=true -Dgysf.smoke.visualControl=true -Dgysf.smoke.motion=true -Dgysf.smoke.waterDomainMotion=true -Dgysf.voxy.validateCoverage=true -Dgysf.smoke.requireDriverBudget=true -Dgysf.smoke.memoryPressure=true'
$env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true'
.\scripts\Build-26.3.ps1 -Joint -ValidateVulkan
```

Remaining requirements include a common world-space shoreline attenuation field,
wave-aware camera-medium classification, large/coarse and shared-edge traversal
qualification, static/animated near-versus-LOD controls, guide/depth readbacks,
and frame-time/memory measurements with the new physical geometry. The current
encoder's null envelope remains fully wet, which cannot establish shore
continuity. The game's CPU camera-water test still follows the rest surface.
Default activation and deployment wait for these requirements, not merely a
successful build or lifecycle check.
