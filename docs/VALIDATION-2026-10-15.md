# Initial 26.3 Vulkan integration evidence

Host: Windows 11, JDK 25, Minecraft 26.3, Fabric Loader 0.19.5, NVIDIA RTX 5060
Laptop GPU (8 GiB), driver 616.92. All runtime checks use isolated development
worlds. No PCL instance, player world or installed mod was modified.

## CPU and packaging

- `check compileSmokeTestJava`: 123 JUnit tests, zero failures/errors.
- Shader ABI, SPIR-V validation and production JAR metadata checks passed.
- Slang 2026.18; NGX shim built from source against DLSS SDK v310.7.0.
- Local JAR includes NVIDIA ray reconstruction/frame-generation runtimes.
  FSR, NRD and XeSS were not built or validated here.
- The development-only smoke mod is excluded from the production JAR.

## GPU runs

| Scene | Result | RT frames | Peak Voxy meshes | Peak RT LOD sections |
| --- | --- | ---: | ---: | ---: |
| gysf alone, SVGF, OMM disabled | Passed | 3330 | 0 | 0 |
| gysf + vooxxy, SVGF, OMM disabled | Passed | 3271 | 336 | 359 |
| gysf + vooxxy, default DLSS-RR + OMM | Passed, SDK validation caveat below | 3674 | 336 | 357 |
| gysf + vooxxy, default plus Nether round trip | Passed, same SDK caveat | 4561 | 336 | 359 |
| Final paired build, DLSS-RR, OMM disabled, Nether round trip | Passed, validation clean | 4634 | 336 | 355 |

These runs enter a fixed-seed world, move/teleport the camera, reload resource
packs, save, disconnect and stop. The joint runs load no Sodium. Vulkan and
synchronization validation report no VUID or synchronization hazards in the
SVGF/OMM-disabled baseline and joint runs. Default runtime created the DLSS-RR
feature and rendered after resource reload without device loss.
The extended default run additionally visits the Nether and returns to the
Overworld, recreating provider/RT world resources. Development settings are
854x480, 1 sample per pixel, 2 bounces and a 512-block external LOD cap; these
counts establish activity, not a performance benchmark.
The final run uses vooxxy commit `5c23b2219c3da076dd97b77cd999489f5f761edd`
and gysf code commit `d2afadc`. DLSS-RR stays active and only OMM is disabled to
avoid the SDK checker defect. It completes 4634 RT frames, resource reload and
the dimension round trip with no VUID, synchronization hazard, device loss or
provider/compositor failure. Both projects' final `check` tasks also pass.

vooxxy standalone Sodium/Vulkan lifecycle regression also passed block
placement/removal and light ingestion, 38,303 block-state persistence round trips,
renderer toggle/rebuild, FOV/window/distance changes, resource reload, Nether
round trip, save and exit. Its GPU wrapper counters return to zero. That run
reports no Vulkan VUID or synchronization hazards; Minecraft recovered from
a swapchain-out-of-date warning during window resize.

The first exploratory joint run lost the device when the provider advanced
Minecraft uploads from inside an RT frame. Provider advancement now occurs at
the start of the client tick, before RT frame command recording. The completed
runs above use this ordering and a shared device host lock.

## SDK validation caveat

Installed Vulkan SDK 1.4.341.1 reports `pInfos-10904` for a zero micromap index
address with `VK_INDEX_TYPE_NONE_KHR`. The code supplies the zero address
required by the specification. The SDK-tagged validation source mistakenly
reports zero (`if (!address)`); current Khronos source checks nonzero instead.
No workaround that violates Vulkan's contract was added to the renderer.

- [SDK-tagged checker](https://github.com/KhronosGroup/Vulkan-ValidationLayers/blob/vulkan-sdk-1.4.341/layers/core_checks/cc_ray_tracing.cpp)
- [Current checker](https://github.com/KhronosGroup/Vulkan-ValidationLayers/blob/main/layers/core_checks/cc_ray_tracing.cpp)

This is not described as a validation-clean default run. Revalidation with a
fixed checker remains useful. Unrelated Windows OSHI performance-counter
registry warnings also occur during Minecraft startup.

## Initial colour-proxy acceptance limits

Runtime data flow is demonstrated, not final visual acceptance. The current
colour proxy loses per-texel alpha/PBR and sub-block/fluid shape details. Sparse
visited terrain can leave incomplete far coverage. A screenshot cannot prove
seam-free lighting, temporal stability, correct reflections or production
performance. These limits describe the initial 0.1.0 prototype above.

## Textured consumer: gysf 0.2.0 and vooxxy Vulkan 5

Both projects pass `check`: 132 gysf tests and 22 provider API tests, with zero
failures/errors. The nine added CPU tests independently check face orientation,
sub-block depth boundaries, greedy UV repetition, fluid top/side heights,
rebasing, metadata identity, bucket layout and immutable snapshot ownership.
Shader compilation, SPIR-V validation, reflected ABI and production JAR checks
also pass. The provider commit is `2b77524e3b080108be7a875e9ea442abf14edfc0`.

| Textured scene | RT frames | Peak source meshes | Peak RT sections | Packed textured batches | Final atlas slot |
| --- | ---: | ---: | ---: | ---: | ---: |
| Fixed-seed world and dimension round trip | 4420 | 336 | 355 | 1520 | 7 |
| Added isolated material fixture and dimension round trip | 4166 | 421 | 446 | 1639 | 8 |
| External foliage cutout policy, visual control and full lifecycle | 4590 | 421 | 437 | 1601 | 7 |
| Final base-alpha coverage, short visual control | 2474 | 421 | 452 | 856 | 3 |

Both runs enable DLSS-RR and disable OMM to avoid the installed SDK checker bug.
They complete movement, resource reload, Nether travel and return, saving and
shutdown with zero VUID, synchronization hazard, device loss or external-provider
failure. The typed path is required by the harness: it rejects zero textured
batch count, an empty typed snapshot or an unbound atlas after returning.
The last full run also verifies nonzero leaf-cutout metadata (83,543 converted
leaf quads across its batches). The fixture's near RT section reports not ready,
and hiding only textured LOD hits removes the fixture at the same camera. That
control establishes that the samples are rendered through the LOD path.

Visual inspection additionally identified alpha mip averaging closing foliage
holes. Any-hit now reads base-level coverage, independently of the closest-hit
ray-cone mip choice for albedo. The final alpha visual run is recorded below.

The production renderer now samples per-face RGBA, applies conditional biome
tint, clips alpha and preserves Voxy's fractional/fluid geometry. Full per-sprite
normal/specular PBR, sampled distant emitters, comprehensive handoff/reflection
acceptance and total-residency memory pressure testing remain open. Sparse
visited terrain still leaves gaps in the isolated camera's uncached horizon.

The final short run uses the same packaged provider (`cd7fb2e3`) and requires
typed meshes, a bound atlas and foliage cutout metadata (57,384 converted leaf
quads). It reports no Vulkan/provider/compositor failures, restores the atlas
after the control, saves and exits. This 990-tick visual test does not repeat
resource reload or dimension travel; the 4590-frame run above covers those.
CPU tests remain 132 + 22 passing after the shader change.

The 30-degree-FOV fixture is at z=100, beyond its ready near RT sections
(`fixture near-ready=false`). In the first image, glass texture and leaf holes
are visible. In the same-camera control, only textured Voxy hits are rejected;
the fixture disappears. This establishes the LOD texture/coverage path, not
universal scene or performance acceptance.

![Textured LOD fixture](images/textured-lod.png)

![Same camera with textured LOD hidden](images/textured-lod-control.png)

Raw logs and screenshots stay locally under ignored `artifacts/2026-10-15/`.

## Atomic 3D publication: gysf 0.2.1 and vooxxy Vulkan 5

The consumer previously bypassed DH's initial 2D coarse filter but still used
the 2D progressive eviction path. Ready geometry could therefore retire a
different vertical layer, and changed multi-batch sources could mix old and
new revisions. Voxy now stages complete overlapping 3D branches and switches
them atomically, retaining empty leaves as explicit replacements. DH's input
continues to use its own 2D policy. Hidden replacement resources remain owned
by the build session until publication or cancellation.

`check` passes 142 gysf tests plus 22 provider tests (164 total), with no
failures/errors. The ten new transition tests include 100 shuffled completion
orders that retain the full covered volume and never publish overlapping
branches. Multi-batch source updates, coarsening, negative coordinates,
vertical separation and empty replacement markers are covered. Shader/SPIR-V,
Minecraft ABI and production JAR checks also pass.

The extended joint command uses the usual 854x480, 1 spp, 2-bounce, 512-block
settings, DLSS-RR active and OMM disabled for the SDK issue above:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dcaustica.rt.omm=false -Dgysf.smoke.textureFixture=true -Dgysf.smoke.visualControl=true -Dgysf.smoke.motion=true -Dgysf.voxy.validateCoverage=true'
$env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true'
.\scripts\Build-26.3.ps1 -Joint -ValidateVulkan
```

This 2900-tick route reloads resources, visits the Nether and returns, traverses
both positive and negative X coordinates while changing height, forces an RT
LOD refresh during movement, teleports to another quadrant, returns to the
fixture, saves and exits. The harness verifies observed player coordinates,
replacement activity and published partition checks, in addition to textured
meshes, foliage coverage, atlas binding and compositor health.

Final paired run (`smoke-coverage-final.log`, provider `616215e3`):

| Measurement | Result |
| --- | ---: |
| RT frames | 5657 |
| Peak selected source meshes, including empty coverage | 890 |
| Peak resident RT BLAS sections | 671 |
| Packed textured batches | 2392 |
| Final atlas slot | 7 |
| Converted foliage cutout quads | 160584 |
| Completed atomic groups, including bootstrap/reuse | 2309 |
| Groups replacing existing source geometry | 886 |
| Validated published partitions | 176 |

Observed motion spans X=-383.5..464.5 and Y=80..192. The manual refresh is
recorded during that route. Resource reload and Nether/Overworld round trip
complete; the final refresh is still streaming when save/shutdown cancels its
unpublished work. There are zero Vulkan VUIDs, synchronization hazards, device
losses, coverage/revision assertion failures, provider failures or compositor
failures. Existing optional-DH class and Windows OSHI counter warnings remain.

Visual inspection shows the fixture after the motion/teleport return. The
same-camera control still removes the textured fixture and far terrain while
keeping the native near-terrain path enabled. This establishes the exercised
LOD path, publication invariants and lifecycle. It does not establish
crack-free geometric silhouettes, matched near/far water/PBR shading, temporal
performance or bounded long-travel VRAM. Those remain separate work.

![Fixture after the extended motion route](images/coverage-motion-return.png)

The first exploratory run also passes with 5635 frames, 698 replacement groups
and 1985 partition checks. Its per-source checkpoint cadence was reduced before
the final run: independent new sources and same-key updates use bounded/time
checkpoints; completed topology replacements can publish immediately. The two
runs encounter different cached terrain and are not a timing comparison.

The paired JARs, SHA-256 manifest, final screenshots and raw build/runtime logs
are retained locally in `artifacts/2026-10-15/coverage/`.

## Native LOD budget: gysf 0.3.0 and vooxxy Vulkan 6

`check` passes 155 gysf tests and 25 provider tests (180 total), with no failures
or errors. New tests cover concurrent reservations, VMA actual-size padding,
rollback/destruction accounting, cached process-headroom estimates, unavailable
driver estimates, reclaiming metadata, distance limits and recovery hysteresis.
Shader/SPIR-V, Minecraft ABI and production JAR checks pass. See
[GPU-MEMORY.md](GPU-MEMORY.md) for the accounting scope and primary references.

The first extended joint GPU run enables memory-budget pressure injection,
driver-budget assertions, 3D coverage validation, the material fixture and
motion route. It still uses 854x480, 1 spp, 2 bounces, a requested 512-block
LOD range and DLSS-RR with OMM disabled for the SDK checker issue.

At tick 400 the isolated process quota changes to 1 MiB. Five allocation
deferrals reduce the temporary provider distance to 256 then 128 blocks. The
existing published native working set decreases from about 148 MiB to 18 MiB
as out-of-window geometry retires; it is not forced below a newly lowered
1 MiB quota by dropping visible fallback geometry. There are 199 ticks with a
resident fallback during the injected pressure window. Restoring the original
quota at tick 600 recovers distance through 256, 384 and the requested 512
blocks before resource reload at tick 1000.

The driver extension is active and reports a 7131 MiB budget for the largest
device-local heap. The default native LOD quota is 1782 MiB. These are the
driver's current guidelines, not a physical-VRAM guarantee. The sampled process
usage includes Minecraft, provider resources, RT resources and vendor runtimes;
the provider-owned counter is about 612 MiB and is not added to driver usage.

| Injected-quota run | Result |
| --- | ---: |
| RT frames | 5596 |
| Peak selected source meshes | 932 |
| Peak RT BLAS sections | 618 |
| Converted textured batches | 2375 |
| Replacement groups / validated partitions | 475 / 166 |
| Allocation deferrals / distance reductions / recovery steps | 5 / 2 / 3 |
| Peak accounted native LOD allocation bytes | 202213936 |
| Native LOD allocation bytes at shutdown | 0 |

The run completes resource reload, Nether/Overworld round trip, variable-height
motion, teleport, manual RT refresh, save and shutdown. No Vulkan VUID,
synchronization hazard, device loss, coverage assertion, provider failure or
compositor failure is reported. The existing OSHI/optional-DH startup warnings
remain. The injection tests the consumer's quota path; it does not deliberately
exhaust the physical GPU or prove behavior under every driver-heap pressure
event. Extended travel, other GPUs and full-process allocation control remain
outside this acceptance.

The independent default-budget run uses the final provider `20c82bde` and
requires zero deferrals (`gysf.smoke.noMemoryDeferrals=true`). It completes the
same 2900-tick lifecycle/motion route with 5655 RT frames, peak 942 selected
source meshes, 955 resident RT BLAS sections, 2468 converted textured batches,
660 replacement groups and 262 validated partitions. The native LOD accounting
peak is 300358048 bytes (about 286.4 MiB), including transient and retiring
allocations. Deferrals, distance reductions and recovery steps are all zero;
the temporary limit remains cleared. Accounted native LOD allocation bytes are
zero at shutdown. Driver-budget and compositor assertions pass, with no Vulkan
VUID, synchronization hazard, device loss or provider failure.

Visual inspection of the default fixture shows per-face textures, cutout leaves,
glass and cached coastal terrain. The injected-quota view can still have an
unfinished horizon while the restored selection streams in; restoring a
distance limit does not mean all newly requested acceleration structures are
already built. This is not a geometric seam, temporal-image or performance
acceptance. The two runs visit/reuse different cached selections, so their
working-set peaks and frame counts are not a controlled performance comparison.

The final paired JARs, hashes, screenshots and raw records remain in ignored
`artifacts/2026-10-15/memory/`. The production JARs exclude the smoke mod. No PCL
mods, configuration or player saves are changed by these runs.

## Water tint and near-terrain ownership: 0.3.1

gysf 0.3.1 uses the paired provider `20c82bde` without a provider API change.
The near-ready probe now follows the represented voxel's inward face axis,
rather than the incoming ray direction. Voxy's sloped fluid normal remains a
shading normal; its packed face determines ownership. Hit positions and the
readiness mask share the terrain rebase.

Water tint is now normalized biome colour, matching the near fluid mesher's
optical input. Ordinary block albedo stays linear. The baked water sprite no
longer multiplies colour or masks water coverage, including shadow tint.
Outward LOD water crossings use the existing dielectric exit handler, while
entering crossings retain the glossy approximation and cannot push a new
absorbing medium from sparse LOD data. This is deliberately asymmetric.

All 157 gysf tests pass, including golden biome-colour packing values and the
separation of sloped fluid normals from packed voxel faces. Shader/SPIR-V,
Minecraft ABI and production JAR checks pass. The unchanged provider's 25 tests
were last checked in the preceding memory-budget validation; they are not a new
provider test run for this change.

The water fixture is a 32-block-wide quartz-bottomed pool across z=16..144.
Server-side checks verify water and floor along its entire centre line. At the
above-water camera, (64,110,32) is near-ready and (64,110,128) is not. The harness
also requires converted textured water, records a hidden-LOD control at the same
camera, and verifies that the later camera's eyes are underwater. Its temporary
forced chunks are removed before the dimension round trip.

An initial setup run failed because fill commands extended into unloaded
chunks; the underwater-camera assertion rejected that incomplete fixture. It
is retained locally as `smoke-incomplete-fixture.log`, not counted as a GPU
pass. Temporary test-world chunk loading and the complete-lane assertion fix
the fixture. No PCL world is involved.

The tint-only intermediate build completes 5981 RT frames, with native LOD
allocation peak 231086272 bytes and zero accounted bytes at shutdown. The final
exit-enabled build completes the same full 2900-tick route:

| Final water run | Result |
| --- | ---: |
| RT frames | 6939 |
| Peak selected source meshes / resident RT BLAS sections | 1036 / 725 |
| Converted textured batches | 2770 |
| Replacement groups / validated partitions | 1172 / 168 |
| Converted water quads, cumulative across rebuilds | 894512 |
| Peak accounted native LOD allocation bytes | 236665632 |
| Native LOD allocation bytes at shutdown | 0 |
| Memory deferrals / distance reductions | 0 / 0 |

The final run completes reload, Nether/Overworld return, variable-height travel,
teleport, manual RT refresh, save and shutdown. Real driver-budget reporting is
active. Neither complete run reports a Vulkan VUID, synchronization hazard,
device loss, provider/compositor failure or coverage assertion. OMM remains off
for the installed SDK checker issue. These frame and working-set counts are
activity evidence, not a controlled performance comparison.

![Water with near and far terrain](images/water-lod-above.png)
![Same camera with textured LOD hidden](images/water-lod-hidden.png)
![Underwater view](images/water-lod-underwater.png)

The hidden control confirms that the far pool and cached terrain are supplied
by textured LOD. The above-water view contains reflected sky and pool geometry;
the underwater view retains a dark band near the surface in this 2-bounce
setup. Its cause has not been isolated. These images do not establish a visual
improvement from the exit path or a complete near/far water match. Incoming
far-water transmission/absorption, waves, geometric quantization and the
remaining dark band still require separate visual controls. The ownership and
optical-input changes are narrower than seamless-water or performance acceptance.

Raw records, intermediate/final screenshots and paired JARs stay in ignored
`artifacts/2026-10-15/water/`. The production JAR excludes the smoke mod. PCL
mods, settings and player saves remain untouched.

## Underwater dark-band diagnosis

The isolated `gysf.smoke.waterMatrix=true` run freezes wave speed at zero and
records the same underwater camera with LOD hidden, waves off, 8 bounces, and
denoising off at 8 and 2 bounces. It reloads resources but deliberately skips
the dimension/motion route, finishing at tick 1720. No player instance is used.

Hiding LOD leaves the broad dark band. More bounces reduce it. Source inspection
finds that Pass A's unsplit TIR/pure-transmission branches use ordinary displaced
point offsets, while split branches and Pass B use `waterWaveContinueOrigin`.
An underwater crest can therefore restart its reflected ray above the original
BLAS plane and hit that plane again, spending path depth on a false interface.
Both unsplit branches now use the same wave-aware continuation origin.

`scripts/Test-WaterOrigins.ps1` compiles the production Slang water module to its
host backend, builds a small MSVC runner, and executes 2080 crest/trough,
top/bottom and outgoing-direction cases. It checks origins against both planes
and reproduces a positive-distance rest-plane self-hit for the old TIR offset,
with no positive hit for the corrected origin. All 2080 cases pass. The unused
host scene binding is opaque; no GPU scene traversal or Java math copy is used.
This optional probe requires Slang, CMake and MSVC and is excluded from the mod.

The original 157 Java tests, shader/SPIR-V, ABI and JAR checks also pass. Paired
before/after GPU matrices complete with no Vulkan VUID, synchronization hazard,
device loss or compositor/coverage failure; native LOD bytes return to zero.

![Frozen-wave underwater view before](images/underwater-tir-before.png)
![Same 2-bounce setting after the origin fix](images/underwater-tir-after.png)

In the fixed 854x480 ROI x=520..853, y=120..244, pixels with every RGB channel
below 20 decrease from 9768 to 129 (98.7%). With LOD hidden the corresponding
counts are 9788 and 188. Raw 2-bounce counts decrease from 55 to 0. These are
narrow dark-band diagnostics, not a general image-quality score or benchmark.
Low path caps can still truncate legitimate multi-interface paths, and distant
water-volume attenuation remains visible. The origin fix addresses false
self-intersections without changing the bounce budget.

## Shared near/far water path: 0.4.0

API 5 water now selects `MATERIAL_WATER` for entering as well as exiting hits,
using the near fluid material's parameters and normalized biome tint. Its
Fresnel, refraction, volume absorption, geometric waves, caustics and guide
generation therefore follow the same shader path as near water. Legacy DH and
colour-proxy entry hits keep their separate glossy approximation. This changes
the typed consumer only; the paired provider remains `20c82bde`.

Reflected guide probes also keep the original BLAS plane in `SpecSurface` and
use the same wave-aware origin as the radiance continuations. Their returned
distance is reconstructed from that actual probe origin, not from the displaced
surface point. This fixes the remaining mismatch between reflected guide and
radiance origins without changing the payload or provider ABI.

The production 0.4.0 JAR passes all 157 Java tests, shader/SPIR-V, Minecraft ABI
and JAR checks. The production Slang origin probe passes all 2080 cases and its
TIR negative control. The JAR excludes both smoke and host-test code. The
unchanged provider is not rebuilt or retested for this consumer-only change.

The final 2900-tick joint run uses the product-default 4-bounce cap, 854x480,
1 spp, DLSS-RR, a requested 512-block LOD distance, coverage validation and real
driver-budget assertions. The water fixture's phase is held still for captures
and restored before travel. OMM remains disabled for the installed SDK checker.

| Final lifecycle/motion run | Result |
| --- | ---: |
| RT frames | 5448 |
| Peak selected source meshes / resident RT LOD BLAS sections | 1347 / 1052 |
| Converted textured batches / cumulative water quads | 2360 / 708345 |
| Replacement groups / validated partitions | 280 / 157 |
| Peak native LOD allocation bytes / shutdown bytes | 336431744 / 0 |
| Allocation deferrals / distance reductions | 0 / 0 |

It completes resource reload, Nether/Overworld return, variable-height travel,
teleport, manual RT refresh, save and shutdown with no Vulkan VUID,
synchronization hazard, device loss or compositor/coverage failure. The first
post-reload underwater screenshot is not used for far-water visual acceptance:
only 30 of 991 new LOD batches are ready near capture time, producing a missing
far wall while the new atlas/scene repopulates. A resource epoch reset cannot
keep drawing invalid old-atlas data; repopulation remains progressive.

Two separate `waterCompareOnly` runs remove reload, dimension changes and travel
from the comparison. Both use the same cameras, frozen wave phase, FOV and
4-bounce cap. Server checks verify the whole pool centre line. The mixed run
has 64/129 near-ready centre-line samples and requires the far sample to remain
LOD-owned; the 12-chunk reference requires all 129/129 samples to be near-ready.
Both underwater captures have 1037 resident LOD BLAS instances elsewhere in the
scene, so the comparison does not confuse a just-cleared LOD scene with water.

| Stable visual run | RT frames | Peak native LOD bytes | Shutdown bytes |
| --- | ---: | ---: | ---: |
| Mixed near + API 5 water | 3235 | 342157744 | 0 |
| Full-near pool reference | 3385 | 344005344 | 0 |

Both stable runs finish without Vulkan findings, coverage/compositor failure or
allocation deferrals. These counts describe completed work, not performance.

![Mixed near and API 5 water](images/water-mixed-final.png)
![Same camera with the pool entirely near-ready](images/water-full-near-reference.png)
![Stable mixed underwater view](images/underwater-final.png)
![Stable full-near underwater reference](images/underwater-full-near-reference.png)

The pool surface, floor and reflections remain close across the two input
paths, without the broad black band or a missing far wall in these stable
captures. In the same dark-band ROI used above, the final 4-bounce mixed and
near-reference counts are 24 and 18 near-black pixels. These are not a causal
comparison with the earlier 2-bounce counts. Cloud phase, background coverage
and stochastic samples differ between processes; this is not pixel-equality,
temporal stability or universal seamless-water acceptance.

API 5 fluid heights still have three-bit 1/8-voxel precision, and distant cells
still simplify geometry. More precise flow/shoreline heights, refraction-aware
detail selection, different resource packs and longer travel remain separate
acceptance work. Source, screenshots, paired JARs and raw records are kept in
`artifacts/2026-10-15/underwater/`. No PCL files or player saves are changed.

## Fluid height continuity: 0.5.0 / provider Vulkan 7

The provider now exports optional API 6 UNORM16 corner heights before its legacy
three-bit raster quantization. gysf copies that sidecar with the immutable mesh
and uses it for RT top and side vertices. Both sides of the water handoff retain
the shared 0.4.0 optical path. The old packed quad layout and API 5 reader fallback
remain supported. Standalone raster generation does not allocate the sidecar.

All 161 gysf Java tests and 32 provider Java tests pass, together with shader,
SPIR-V, Minecraft ABI and JAR checks. New cases cover all four side/top edges,
multiple voxel scales, immutable ownership, bucket order and reuse, and 10,001
height inputs. UNORM16 error is bounded by about half of 1/65535 of a source
voxel before FP32 position rounding; this does not recover coarse world detail.

The isolated still-water cell at (64,111,64) is checked through actual provider
export, reflective consumer copying and production RT corner conversion. Its
expected world height is 111 + 8/9. Separate stable 4-bounce runs show:

| Geometry path | Measured world Y | Maximum error in blocks | RT frames | Native LOD peak / shutdown bytes |
| --- | ---: | ---: | ---: | ---: |
| API 6 precise heights | 111.888885 | 0.0 | 2856 | 263355488 / 0 |
| API 6 with legacy-height control | 111.875 | 0.013885498 | 2116 | 214325008 / 0 |

Zero here means equal FP32 world coordinates for this fixture, not exact real
arithmetic for every possible height. The old error is about 1.39 cm at block
scale. Different cache/bootstrap progress makes these frame and allocation
counts unsuitable as a performance comparison. Both runs finish with clean
Vulkan/synchronization validation, no compositor/coverage failure and no
allocation deferrals. Their first probe revision packed one synthetic quad;
the probe now calls the same production corner converter without incrementing
render counters. The initial provider JAR carried the pre-commit `20c82bd`
suffix; its feature source was subsequently committed as `45b4a50b` and rebuilt
as `voxy-vulkan-0.2.20-26.3-vulkan.7-dev-45b4a50.jar` for final verification.

A separate compatibility run loads the real API 5 provider Vulkan 6 JAR, rather
than just hiding API 6 data with a flag. gysf 0.5.0 discovers the missing optional
method and keeps the old height path: 3084 RT frames, 998 peak resident RT LOD
sections, zero precise-height quads, the expected 0.013885498-block legacy error,
and 325652832 peak / zero shutdown native LOD bytes. It completes without Vulkan,
synchronization, compositor or coverage failures.

The pinned provider also passes its standalone Sodium Vulkan lifecycle run:
38,303 block-state round trips, live edits, disabled-renderer control/rebuild,
window/FOV/distance changes, resource reload, dimension return and save/shutdown.
All Voxy GPU wrappers are released, with no Vulkan validation findings. An
earlier attempt exposed a smoke-harness race: terrain invalidation could rebuild
the renderer while the visual control had shut it down but left its desired
state enabled. The harness now disables that state during the control and
asserts it stays disabled; production renderer guards are unchanged.

The final pinned API 6 pair completes the full 2900-tick route at 4 bounces,
854x480, 1 spp and DLSS-RR, with Vulkan synchronization/coverage validation and
real driver-budget assertions. OMM remains off for the installed SDK checker.
A temporary 1 MiB LOD budget at ticks 400..600 deliberately denies allocations;
the test requires published fallback to remain visible and provider selection
to reduce, then recover fully before the resource reload at tick 1000.

| Final lifecycle/motion/pressure run | Result |
| --- | ---: |
| RT frames | 5469 |
| Peak selected sources / resident RT LOD sections | 1347 / 675 |
| Textured batches / cumulative water quads | 2323 / 784482 |
| Cumulative quads using precise fluid heights | 887502 |
| Replacement groups / validated partitions | 286 / 140 |
| Native LOD peak / shutdown bytes | 272630240 / 0 |
| Deliberate allocation deferrals / distance reductions / recoveries | 5 / 2 / 3 |
| Ticks retaining published fallback during pressure | 199 |

The known fluid cell retains the expected precise height in this run too.
The observed motion spans X=-383.5..464.5 and Y=80..192. Resource reload,
Nether/Overworld return, explicit RT refresh, teleport, save and exit all finish
without Vulkan VUIDs, synchronization hazards, device loss or compositor/coverage
failure. Quads and swaps are cumulative work counters, not current residency
or a benchmark. Post-reload captures still show progressive scene repopulation
and are not used as stable water-image acceptance.

This milestone removes a measured height discontinuity. Coarse voxel shape,
fluid/ceiling occlusion parity, flowing shoreline fixtures, resource-pack
variants and temporal transition acceptance remain separate work. Raw records
and paired artifacts are kept in `artifacts/2026-10-15/fluid-api6/`. PCL mods,
configuration and player saves remain untouched.
