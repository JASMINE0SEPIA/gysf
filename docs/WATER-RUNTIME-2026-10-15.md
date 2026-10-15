# Finite water in the game pipeline

Development 0.7 supports `-Dgysf.rt.finiteWater=true` in the isolated client.
The switch is off by default. This is an integration checkpoint, not completed
near/far-water or underwater acceptance, and it has not replaced the PCL JAR.

**Validation audit correction:** the earlier VUID-only search missed two
`vkCmdClearColorImage(): WRITE_AFTER_WRITE hazard detected` reports per process
on `nv.ngx.dlssd.resource` during initial RR evaluation. All pre-fix runs below
remain valid for their stated counters, camera checks and zero-live shutdown,
but are **not synchronization-clean acceptance**. The new log checker rejects
this message even without a VUID or `SYNC-HAZARD` prefix. Similar internal clear
reports appear in [NVIDIA Streamline issue 84](https://github.com/NVIDIA-RTX/Streamline/issues/84);
that related report alone does not establish the cause of this local RR issue.

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
It additionally passes 4,273 runtime checks: full-sphere normal encode/decode,
static half-storage/history agreement, translated crest/trough ownership and
previous-field guide motion/normals, plus nine projection-eye and seven device-depth controls. The maximum normal round-trip vector error
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
and live bytes were zero at exit. Two NGX internal clear hazards were missed by
the original filter; see the validation audit above.

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
bytes were zero at exit; two NGX clear hazards were overlooked. Waves, flat water,
hidden textured LOD, bounce limits and denoising
controls are retained in `artifacts/2026-10-15/water-runtime/static-water-matrix/`.
The captures have no camera-state readback or input lock, so they are exploratory
image evidence, not a strict equal-scene pixel or performance comparison. Visible
far-boundary differences remain unresolved. All 188 Java tests, shader/ABI
checks and JAR validation passed before this run.

## Wave-aware camera medium

Finite mode now dispatches one camera raygen invocation before the image passes.
Two three-block guide rays query the same TLAS/intersection/hit stages used by
the image, including near-ready LOD suppression. A nearest finite-water hit's
entering/exiting flag classifies the initial medium; solid/glass blockers remain
visible to the probes. When neither direction resolves a water interface, the
existing CPU deep/covered-water hint remains in force. This avoids a separate CPU
wave approximation and avoids doing the probe for every pixel. Orthographic
projections retain the previous CPU hint and near-plane ray origins.

The probes and finite-mode primary rays share the perspective optical centre
`inverse(VP) * (0,0,1,0)`, including projection-baked view bob. Primary rays start
there so a water crossing between the eye and near clip is not skipped. Their
direction still comes from unprojected near/far points. The shared Slang C++
projection controls cover translations, rotations, zooms and orthographic fallback;
they are numerical checks, not a claim of GPU image or temporal acceptance.
Device-depth guides clamp projected z/w to [0,1], including interfaces now
reachable between the eye and near plane; physical linear viewZ stays separate.

`WorldPush.waterCamera` is a reflected uint4 rather than repurposed material or
wave lanes. Its x word carries submerged/projection-probed bits; y records the
CPU hint, valid directions and agreement; zw retain hit distances. An RT-write
to RT-read barrier publishes it before primary/indirect shading. Optional
`-Dgysf.rt.validateCameraMedium=true` additionally publishes to host reads; the
host waits for the exact slot's graphics completion, invalidates mapped memory,
and uses a generated reflected field offset. Ordinary play has no host readback.
The GPU never overwrites the CPU's other world data or ready-mask words.

The first camera test deliberately inverted the two flat-water hints and passed
both controls. Its animated expectation was invalid: the authored exponential
spectrum is nonnegative, so a trough need not fall below the rest plane. That
failed assertion/log is retained under `camera-initial-sampling/`; the spectrum
was not changed to make the test pass. The corrected four-phase run in
`camera-four-phase/` passed both flat controls, continuously submerged waves,
and an eye 0.20 blocks above rest that observed 586 wet and 436 dry completed
samples. It rendered 10,264 RT frames, had zero direction disagreements and
zero native live bytes at exit, with Vulkan/sync validation active and no VUIDs.
That checkpoint predates the projection-centre/origin correction.

The corrected projection-centre build then passed all five locked-camera game
controls in `camera-projection/`, with 12,221 RT frames:

| Control | Resolved samples | Wet | Dry | Deliberate condition |
| --- | ---: | ---: | ---: | --- |
| Flat below | 1,562 | 1,562 | 0 | CPU hint incorrectly says air |
| Flat above | 1,097 | 0 | 1,097 | CPU hint incorrectly says water |
| Waves below rest | 1,061 | 1,061 | 0 | Eye stays below the nonnegative field |
| Wave crossing | 1,004 | 512 | 492 | Fixed eye 0.20 blocks above rest |
| Projection translation | 1,556 | 1,556 | 0 | Entity eye above rest; optical centre below |

The last control injects a +0.08 view-space projection translation in the smoke
mod only. It leaves the entity/CPU water hint unchanged, independently testing
the effective optical centre. Across the run, 7,661 completed frames resolved a
finite-water interface, with zero up/down classification disagreements. There
were two startup NGX clear reports; native LOD peak was 346,846,688
bytes and live bytes were zero at exit. Source/ABI/shader checks and all 188 Java
tests passed. The archived JAR SHA-256 is
`DC870DA24F44D750FCAAADB252C8489E51487E314E8C9E2DC74E51A81C3A04CB`.
These are camera-medium, build and resource checks, not equal-scene optical,
guide-image or frame-time acceptance. Player-instance JAR hashes still match the
previously installed 0.6.2/provider pair.

The new camera stage also passed the gated moving lifecycle
(`camera-enabled-lifecycle/`): 19,835 RT frames, resource reload, Nether round
trip and the positive/negative terrain-origin route. Observed water-child peaks
were 140 near, 852 LOD and 964 packed in a TLAS. Injected one-MiB pressure
produced one deferral/reduction, two recoveries and 199 fallback ticks, with
recovery before reload. Native LOD peak was 697,678,416 bytes and live bytes
were zero at exit; two NGX startup clear reports were present. This
run predates the final device-depth clamp and does not measure guide values.

After the device-depth correction, the default-off compatibility lifecycle
also passed (`camera-disabled-lifecycle/`): 20,688 RT frames, resource reload
and Nether round trip, no deferrals, native LOD peak 664,626,240 bytes and zero
live bytes at exit. Two NGX startup clear reports were present.

## Qualified build after NGX repair

The repaired SDK integration in `0214e29` and the camera/depth changes then passed
the strict five-phase control (`artifacts/2026-10-16/qualified-water-camera/qualified-camera/`):
12,184 RT frames, 7,552 resolved camera samples and zero direction disagreements.
The fixed eye in the wave field observed 654 wet and 380 dry samples; the
projection-offset control resolved 1,560 wet samples despite the CPU air hint.
Two actual NGX clear callbacks executed. The strict checker found no VUID,
bare READ/WRITE_AFTER_* hazard or Validation Error report. Native LOD peak was
347,088,384 bytes and shutdown live bytes were zero. All 188 Java tests and
shader/ABI/JAR checks passed for this build; the shared Slang C++ probe passes
4,096 root cases and 4,273 runtime cases with zero failures/exhaustions.

The development JAR SHA-256 is
`EF31804F2951E07C89DD09CBB7C3A62E06FF1EE55E6A46A1CDF87CDE79218123`.
The loaded NGX shim is
`51D1D46073D85297D664CFEBB68FB48540996CD0236AE3B4C8F05B52CE5EFCF7`.

The same source/native combination also passed both full lifecycle controls,
each accepted by the strict checker with live RR and actual clear callbacks:

| Mode | RT frames | Native LOD peak bytes | Exit live bytes | Scope |
| --- | ---: | ---: | ---: | --- |
| Finite on | 19,268 | 707,968,800 | 0 | Reload, Nether round trip, signed-origin travel, injected pressure |
| Finite off | 20,968 | 664,625,200 | 0 | Reload, Nether round trip, no memory deferrals |

Finite-on observed 137 near children, 884 LOD children and a peak 996 packed
water instances. One-MiB pressure produced one deferral/reduction, two recoveries
and 199 fallback ticks; full distance recovered before reload. In total the
three final controls rendered 52,420 RT frames without a strict-checker report.
This does not qualify OMM-on operation, equal-scene frame time, all shoreline
topologies, or GPU normal/depth image accuracy. The finite-water switch remains
off by default. Final logs/captures and the tested JAR are retained under
`artifacts/2026-10-16/qualified-water-camera/`.

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
camera-medium coverage in edited/waterlogged/covered topologies, large/coarse and shared-edge traversal
qualification, static/animated near-versus-LOD controls, guide/depth readbacks,
and frame-time/memory measurements with the new physical geometry. The current
encoder's null envelope remains fully wet, which cannot establish shore
continuity. The CPU fallback intentionally still follows the rest surface.
Default activation and deployment wait for these requirements, not merely a
successful build or lifecycle check.
