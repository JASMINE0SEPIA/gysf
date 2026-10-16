# Shared water field in GPU traversal

Finite water now binds optional provider capability 1 through two reflected
WorldPush addresses. The intersection, closest-hit normal and previous-frame
motion paths sample the same canonical source-water field. Unsupported providers
keep address zero and the existing envelope. Missing or unknown nodes in a
supported provider suppress displacement conservatively; they are never declared
known air. The feature still requires `gysf.rt.finiteWater=true`, off by default.

## Data and lifetime

An 80-byte water primitive retains its layout; previously unused v2.w stores the
quad's section-local source-water layer. Both halves use the maximum of all four
corners, with `floor(nextDown(maxY))` handling exact full-cell tops, negative
heights and large local float spacing. Source layer selection for a coarse face
with varying source elevations remains part of the open rest-topology problem.

Only published near and LOD geometry contributes node-owner requests. A bounded
nearest-tile heap keeps at most 4096 unique keys, independent of the total scene
size and iteration order. Polling is limited to ten times per second. Immutable
images upload only when the requested tile keys/revisions or world epoch change.

Each BDA image starts with four uint words: hash mask, tile count, format 1 and
word count. Its open-addressed table has eight uint words per entry: signed X,
source Y, signed Z, node-word offset, constant flag and three zero reserved words.
Offset zero marks an empty entry. Node words store u16 clearance and known bit16.
Uniform known/unknown tiles take one word; partially known tiles retain all 256.
Maximum image size is 4,456,464 bytes. Hash comparison checks all coordinates.
Field live/peak counters report requested buffer bytes, excluding allocator
alignment and retained VMA pools; they are not total process VRAM measurements.

Eight reusable generations bound storage, including current and immediately
previous frame data. Each is marked with its exact graphics timeline use; reuse
waits only for that generation. Field updates do not call device-wait-idle. World
epoch changes reset previous-field history. Shutdown frees all generations after
the existing device-idle gate, with a separate live/peak-byte diagnostic.

## Shared surface

Integer terrain anchors plus local integer cell coordinates preserve fractional
positions at signed coordinates and the world border. Four weights are sampled
on a unit cell, independent of a primitive's width or diagonal. Available node
excursion is normalized by the conservative authored displacement bound
`0.55 * waterWaveStrengthScale()`.

Traversal clips the original triangle first, walks canonical cells in ray order,
then bounds and solves the wave multiplied by that cell's smoothstep envelope.
The rest plane and triangle clipping remain those of the original primitive.
No epsilon step leaves gaps at cell boundaries. Uniform tile interiors skip up
to a 15-by-15 region; the outer strips still interpolate neighboring owners.
The walk is bounded to 2048 regions, with the existing bounded root solver in
each region. Very large/coarse primitives and grazing tangencies require further
qualification; reaching a cap is not accepted as an accurate hit.

Closest-hit samples the current and previous immutable fields with the current
and previous wave time. This includes field edits in normals and vertical motion,
instead of differentiating only animation time. Camera, continuation, guide and
shadow rays all use the same procedural intersection path.

## Validation

Java exports the actual production packed current/previous images. An independent
Slang fixture checks node words against analytic source data, gradients against
finite differences, rebases, missing layers, partial known masks and signed/world
border coordinates. Its 256 root cases compare the production walk against dense
bracketing/bisection, compare unit and 16-block primitives, and verify previous
flat normals/motion after an edited field. Cases include mixed shores, uniformly
wet and uniformly flat regions, both ray directions and slider strengths 1/4.

The same fixture executes on CPU and real Vulkan GPU: 4096 cases each, zero
failures, maximum root distance error 0.0000286102 and maximum 84 evaluations
across both coarse halves. GPU synchronization validation reports zero errors.
This is a synthetic compute readback, not a whole-frame performance result or a
qualification of every hit/guide image in Minecraft. The existing host corpus
still passes 4096 root cases and 4273 runtime cases without failures/exhaustions.

The CPU probe uses the same uint-word loads through Slang's documented
[target interoperation](https://docs.shader-slang.org/en/latest/external/slang/docs/user-guide/a1-04-interop.html)
for a host address; SPIR-V keeps physical storage buffer pointers. All lookup,
interpolation, bounds, root solving and guide math remain shared source.

Run after `gradlew test` has generated the two production images:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
$env:VULKAN_SDK='C:\VulkanSDK\1.4.341.1'
.\scripts\Test-WaterField.ps1
```

GPU probes require no concurrent game process. `-CpuOnly` omits GPU execution.
Readback CSVs, packed images and SPIR-V are saved under the chosen output folder.
Minecraft checks and final artifact identities belong under
`artifacts/2026-10-16/water-field/`.

The initial joint game control runs at maximum wave strength with live DLSS RR
and OMM off. It completes 6232 RT frames, three source-field edit phases and 126
GPU field uploads. Final residency is 599/599 requested tiles, 117244 known nodes,
zero pending requests and 2369672 field bytes; field and native LOD exit live
bytes are zero. Strict Vulkan/synchronization log acceptance passes. This run
predates the bounded request-heap refinement, which selects the same nearest
keys while bounding CPU storage.

The subsequent clean optical control uses the final bounded heap, no upper
source/export pool, static waves at strength 4 and speed 0, and live DLSS RR.
It completes 6086 RT frames, with near-ready true and far-ready false at the
pool reference points. Field residency reaches 1938/1938 tiles, 105072 known
nodes, zero pending requests, 261 uploads and 3154228 peak bytes. Both field and
native LOD exit live bytes are zero; strict validation passes. Its above-water
capture no longer has the large brightness boundary present in the stacked-pool
fixture. This is an exploratory scene/capture, not an equal-camera near-only
pixel comparison, guide/depth image readback or frame-time acceptance.

That data/export fixture also constructs a second pool at Y=176 above the
optical pool/camera. It can cast shadows, so its captured near/far brightness
boundary must not be treated as evidence of a renderer seam. Optical controls
must use a clean world without that extra pool. Static/animated clean controls,
material/normal/depth comparisons, temporal guide images, coarse rest topology
and matched-scene frame time remain separate requirements. The PCL mod pair and
player saves have not been changed.

The first moving lifecycle attempt (`lifecycle.log` and its preserved crash
report) is a failed control: at Nether entry, published fallback geometry could
still request Overworld-only heights. Capability 1 correctly rejected those
layers and the consumer latched a compositor failure. Requests now filter to
the active ClientLevel's half-open height interval before heap selection/polling;
a level identity change forces a poll and resets previous-field history. The
identity is weak so this manager cannot retain an exited client world. A unit
regression covers Overworld/Nether ranges and both excluded endpoints. Smoke
startup explicitly returns to Overworld so a previous crash's saved dimension
cannot relocate the next fixture. A repaired lifecycle must pass independently;
the failed attempt does not qualify resource shutdown or dimension stability.

The repaired animated lifecycle (`lifecycle-repaired.log`) subsequently passes
strict acceptance with live RR and actual synchronized NGX clears: 18821 RT
frames, resource reload, Nether return, X=-4351.5..4416.5 travel and terrain
anchors -4352..4416. Injected one-MiB LOD pressure causes one deferral/reduction,
two recoveries and 199 fallback ticks, with recovery before reload. Water-child
peaks are 131 near, 841 LOD and 953 packed in one TLAS. Field residency reaches
3557/3557 tiles with 162888 known nodes and zero pending requests, 518 uploads
and 7065892 peak logical field bytes. Both field and native LOD exit live bytes
are zero. All 207 consumer Java tests, shader/ABI/JAR checks and the eight-case
log-checker regression pass. This establishes the repaired lifecycle under the
stated gate, not complete shoreline optics or a frame-time improvement.

The final default-off clean compatibility control also passes strict acceptance
with live RR: 14117 RT frames, no LOD deferrals, no field residency/shutdown
marker (no field GPU allocations), and zero native LOD live bytes at exit.
Frame counts across these different processes/scenes are not an FPS comparison.

The subsequent [fixed-camera GPU guide comparison](WATER-GUIDES-2026-10-16.md)
finds and fixes static normal-history, biased-reflection motion and a near/LOD
roughness mismatch. Four clean flat-pool controls pass geometry, guide and depth
checks; that document records exact scope and maximum errors. Next work must
extend optical checks to natural topology and animated edits, and quantify
finite traversal and complete-frame cost under matched settings. Varying-height
coarse shore topology, approximate/unvisited source fallback, waterlogged/covered
edits and long-travel temporal quality remain open before enabling by default.
