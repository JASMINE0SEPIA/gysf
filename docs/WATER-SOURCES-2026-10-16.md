# Actual water sources and coincident mesh edges

Production remains `55f72e7`, provider `d4097ba`. This checkpoint adds smoke/native validation,
not a production renderer change. The default-off finite-water gate and PCL installation are
unchanged. The complete cloud build for `55f72e7` passed in
[run 38946565440](https://github.com/JASMINE0SEPIA/gysf/actions/runs/38946565440).

## Live source capture

`Test-WaterSources.ps1` opens its own `gysf-water-source-26.3` world under `run-smoke`. A frozen
96x96 pool contains flowing levels 0..7, six stepped source elevations, a glass bridge and a
stone cover. The near radius is 12, OMM off, finite water/cache on; Vulkan synchronization
validation and NGX clear tracing are active. This is a diagnostic workload, with no FPS claim.

The test checks received block states, acquires the real provider world sections, runs the
provider's actual mesher at L0..L4, and uses production `RtVoxyMesh.corners` and `RtWaterGeometry`
to pack selected water tops. Sampling is bounded to 64 varying and 64 constant quads per mip.
No production shader or exported vertex is patched. It collects 428 source quads:

| Mip | Actual varying-source tops found | Constant-source tops found | Probe cases |
| --- | ---: | ---: | ---: |
| 0 | 0 | 1,016 | 640 |
| 1 | 70 | 889 | 1,280 |
| 2 | 111 | 356 | 1,204 |
| 3 | 55 | 40 | 855 |
| 4 | 8 | 5 | 117 |

The first 4,096-case dataset contains 1,835 varying-source cases; current-strength expectations
include 447 zero, 2,905 fully wet and 744 partial-envelope cases. Independent double-precision
references derive node layers from the original four corner heights and read the source API's
clearances, without reading the GPU image or calling the production domain helpers.

The source request also includes an unvisited region offset by 65,536 blocks in X/Z. All 170
tiles settle: 21,760 known and 21,760 unknown nodes, including 13,596 known-zero nodes. The
unknown control rebases unchanged real primitive packets to that unvisited region in the native
probe. It is a controlled rebase against actual provider responses, not a claim that those
triangles were observed there in the game. Known-zero and unknown are counted separately.

## CPU and Vulkan compute

Both backends read the identical production 80-byte triangle records and packed source image.
Vertical rays test the internal diagonal, independent expected displacement, previous-time
motion, animation-off geometry, explicit unknown-source flat fallback and field-absent/full-wet
controls. The 4,096 cases pass on CPU and GPU; GPU maximum diagonal gap is 0.0000152588 blocks,
maximum root/reference error 0.0000190735 blocks, and validation error count is zero.

This proves source-domain/solver consistency for the captured inputs. The GPU work is a compute
probe calling production intersection/shading functions, separate from the game's hardware-RT
pipeline, TLAS ownership, full shading and reconstruction. `ContinuityQualified` stays false
for this mode; `SourceDomainQualified` records its narrower result.

## Actual adjacent top-edge candidates

`Analyze-WaterSources.py` reconstructs the four corners from both exported triangles and groups
oppositely facing rectangle edges with overlapping spans. It separates original rest-height
differences from additional wave-envelope disagreement. Rest-edge coincidence tolerance is
0.0003 blocks, covering L0-L4 height quantization; exact coincidence is also counted separately.

| Candidate group | Rest-coincident pairs | Exact rest pairs | Node-pair observations | New source/weight mismatches | Old maximum-layer weight mismatches |
| --- | ---: | ---: | ---: | ---: | ---: |
| Same mip | 370 | 370 | 2,314 | 0 / 0 | 309 |
| Adjacent mips | 15 | 7 | 43 | 0 / 0 | 0 |

The old-policy comparison applies the previous whole-quad maximum-layer rule to actual captured
heights and source clearances. Maximum old weight disagreement is 1.0. Six same-mip and three
adjacent-mip node pairs lack a requested old-layer owner and are excluded from that old-policy
comparison, rather than being called dry. Counts are node-pair observations, not unique places.

The analyzer exports a second dataset using **unchanged actual triangle packets** at shared edges,
plus internal controls: 1,956 inter-quad cases and 2,140 internal-diagonal cases, including 2,214
varying-source cases and all five mips. CPU and Vulkan each pass all 4,096. GPU maximum additional
wave gap is 0.0000190735 blocks and maximum root/reference error 0.0000152588 blocks, with zero
validation errors. Separate rest heights are retained in the references; these wave-gap values
do not erase pre-existing geometry differences. `CapturedEdgesQualified` names this scope.

The old synthetic continuity probe also passes 4,096 CPU + 4,096 GPU cases after the shared runner
changes. Its stronger exact-coincident synthetic scope remains separate.

## Remaining geometry and optics work

The top-edge diagnostic also finds ten same-mip and 411 adjacent-mip candidates whose rest heights
do not coincide. Their maximum differences are about 0.51857 and 4 blocks in this deliberately
stepped fixture. These are **not automatically cracks**: the diagnostic omits connecting side
faces and does not determine which mips or near sections are simultaneously selected by live RT.
It cannot justify flattening the mesh or adding skirts blindly.

The next acceptance step must capture the active near/LOD geometry and connecting sides, then
compare actual raw RT interfaces through sloped/covered water while stationary and moving.
Full-game near/far optics, natural-world transitions and player performance remain open.

## Evidence and reproduction

The final complete capture is `artifacts/2026-10-16/water-source-unknown/`: build/game logs,
source hashes, 170-tile source images, original packets and independent references, edge analysis,
both CPU/GPU readbacks, and `synthetic-regression/`. The earlier `water-source-qualified/` run
independently repeats the capture and edge probes before explicit unknown controls were added.

The final game exits with zero live water-field, cache, cache input/source/pending and RT LOD
allocations. Cache peak is 29,228,672 bytes; native LOD peak 364,677,584 bytes, no allocation
deferrals. The log checker confirms live RR/NGX synchronization and no Vulkan/sync errors.
The production JAR is unchanged, SHA-256
`40e8d1cfa61d23d219a46553edf833ba44e9a9aea8e6099b662d713ac7d9cf33`.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
$env:VULKAN_SDK='C:\VulkanSDK\1.4.341.1'
$env:DLSS_SDK=(Resolve-Path '.tools/DLSS-310.9.1').Path
.\scripts\Test-WaterSources.ps1 -OutputDir artifacts/fresh-water-source
```

Fresh output directories are required. The helper and wrapper reject conflicting performance,
cache-lifecycle and visual-test modes; source capture does not alter player worlds or settings.
