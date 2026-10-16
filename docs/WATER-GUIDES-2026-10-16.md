# Water interface and reconstruction guide comparison

This change fixes three independently observed guide/material inconsistencies:

- Finite water used an implicit FP16 current normal, an explicitly rounded and oct-encoded history
  normal, and did not consistently normalize the current normal before optics. Static water acquired
  a false normal delta. Both temporal paths now explicitly round to FP16, and all finite-water optics
  consume a normalized interface. The unreachable oct pair `0x80008000` means exactly unchanged:
  history reuses the decoded current normal without a second quantization. Different history still
  uses the oct codec. Equal phase/domain evaluations also reuse one field result; changed shoreline
  weights must evaluate both fields, including with animation speed zero.
- Reflection motion subtracted the primary pixel from the previous reflected feature. A biased ray
  origin moves the current reflected feature away from that pixel, inventing motion even after the
  normal-history fix. `reflection_motion.slang` projects the current and previous mirror features
  through their respective camera matrices, then subtracts those projections. Behind-camera features
  return no motion. Camera motion, reflected-object motion and interface position/normal motion remain
  represented; this is not a special case that disables animated-water motion.
- Textured Voxy opaque faces retain a block-state material profile, but the legacy DH mini-material
  fallback clamped smooth quartz roughness from .1225 to .25 and discarded metalness. Textured faces
  now retain the compiled profile values. Legacy DH keeps its mini-material mapping. This does not
  establish matching resource-pack PBR texture sampling between near and LOD geometry.

## Raw GPU diagnostic

`-Dgysf.rt.captureGuides=true` enables an otherwise inactive diagnostic. It suppresses jitter for
the entire diagnostic run and requests linear view depth. Each requested frame records the physical
foreground interface before a transmission probe can replace its hit payload: position, flags,
current/previous normal, roughness, material kind and physical displacement. It also copies ordinary
normal/roughness, albedo, hardware depth, motion, specular albedo/motion, linear view depth and raw
radiance **before** denoising/RR, plus the actual GPU-updated WorldPush and immutable water-field images.
`display.png` is an illustrative following-frame screenshot, not the exact raw-data frame.

The queue holds one pending request and one in-flight capture, capped at 2,097,152 pixels. Mapped
copies wait for their exact submitted graphics timeline use before invalidation/readback. Image
layouts are restored before subsequent consumers. Buffers are freed after readback or the existing
device-idle shutdown gate. The test separately requires zero live guide-readback, field and native
LOD bytes at shutdown; these counters do not measure allocator pools or total process VRAM.

`WaterGuideCapture` fixes eye/rotation, disables bobbing, blocks test-camera input and waits for scene
publication/field residency to settle. It takes strength 1 and 4 captures above and below the same
clean quartz pool. Separate processes compare render-distance 4 (both near and LOD water must be
visible) and 12 (pool water must all be near geometry). Tests only use `run-smoke`, never a PCL save.

The Python reader verifies the actual GPU animation speed, wave strength, zero jitter, fixed camera,
unchanged projection/field history and camera medium. Comparison requires identical pixel rays,
finite data, zero interior material-kind mismatches, and a well-conditioned surface-position/normal
p99 error below .002 blocks/vector units. It reports all shared interface pixels separately; grazing
pixels are not silently removed from those distributions. Static checks require normal/physical
history errors below 1e-7, unit-normal error below 1e-6 and maximum reflection motion below .001 render
pixels. Both near-owned and LOD-owned interiors must independently contain over 100 compared pixels;
their maximum position/normal errors and the maximum linear-depth error must stay below .002. No
reference interior may disappear from the comparison. The clean pool's maximum ordinary
normal/roughness differences must also stay below .002.

## Controls and regressions

Artifacts are under `artifacts/2026-10-16/guide-compare/` (ignored binary/local evidence).

The initial `mixed`/`near` runs completed and passed Vulkan log validation but **are not a complete
static comparison**: a legacy tick-1280 script action reset wave speed to 1 in `mixed/under-1` and
`near/above-4`. Those controls remain preserved. The action is excluded in guide-capture mode, and the
reader rejects animated input from the actual GPU parameter bytes rather than trusting a PASS label.
The valid original `above-1` pair had zero pixel-ray differences, zero interior water-kind mismatches,
and a conditioned position p99 of 0.0000307551 blocks. Its ordinary roughness difference was .127502,
consistent with the .1225-to-.25 clamp.

`mixed-fixed` corrects static field/normal transport but still uses primary-pixel reflection motion:
normal and physical history are exactly zero in all four phases, while maximum reflection motion is
.03447/.03851/.14882/.23529 pixels. Three phases fail the static motion gate. This failed intermediate
control is retained rather than counted as complete.

`mixed-reprojection` adds two-frame reflected-feature projection, before the material correction.
All four static checks pass: normal/physical history errors are exactly zero, and maximum reflection
motions are .00016452/.00017926/.00013372/.00018320 pixels. Strict Vulkan/synchronization, real RR/NGX
clear and capture/field/native-LOD shutdown checks pass. This is an isolated diagnostic, not a frame
rate result or complete visual qualification.

The shared Slang host probe now checks 8587 runtime cases, including full-sphere unchanged/changed
normal codecs, frozen/reset wave history and 66 reflected-feature cases. The latter cover biased
origins, grazing/sky endpoints, moving cameras/content/interfaces, changed interface normals and
behind-camera rejection against independent axis-aligned mirror/perspective expectations. All pass,
along with the existing 4096 root cases. The canonical field fixture still passes 4096 CPU and 4096
Vulkan GPU cases, with zero failures/validation errors and maximum root error 0.0000286102 blocks.
The Java suite has 207 passing tests; shader, ABI and packaged-JAR checks also pass. The log checker's
12 fixtures include missing/incomplete captures and nonzero readback shutdown rejection.

`final-pair` includes all three fixes and a full mixed/near capture pair at 427x240 trace resolution
(854x480 display). Its two processes pass strict Vulkan/synchronization, real RR/NGX clear and all
resource-shutdown gates. `all-pixel-analysis` strengthens the initial paired analysis to require
maximum-error checks for each owner, including grazing rays; all phases pass. No reference interior
pixels are missing, and ordinary roughness differences are exactly zero throughout.

| Phase | Compared LOD pixels | Position p99, all shared (blocks) | Maximum LOD position error (blocks) | Maximum ordinary normal error |
| --- | ---: | ---: | ---: | ---: |
| Above, strength 1 | 7058 | .0000307551 | .0000323688 | 0 |
| Above, strength 4 | 7024 | .0000316873 | .0002335393 | .0004882813 |
| Below, strength 1 | 451 | 0 | .0016000324 | .0000917168 |
| Below, strength 4 | 537 | .0000108790 | .0006432291 | .0002441406 |

Current/previous physical normals and water displacement are exactly equal in both processes for
all four phases. Maximum reflection motion stays below .000184 render pixels. Ordinary linear depth
has maximum error below .001587 blocks. The preserved original `above-1` pair fails the strict static
gate, confirming that the acceptance check rejects the pre-fix input. The shared temporal spectrum
probe additionally passes 12960 cases with zero current/previous height or normal error.

The final `default-off.log` compatibility run passes 10951 RT frames with live RR and strict Vulkan
synchronization acceptance, zero native LOD bytes at exit, and no field/readback allocation markers.
This control keeps normal production jitter and the existing non-procedural water path.

## Reproduce

Set Java 25, Vulkan SDK and the locally staged DLSS SDK, then run with no other Minecraft process:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
$env:VULKAN_SDK='C:\VulkanSDK\1.4.341.1'
$env:DLSS_SDK=(Resolve-Path '.tools/DLSS-310.9.1').Path
.\scripts\Test-WaterGuides.ps1 -OutputDir artifacts/my-fresh-guide-comparison
```

The script builds/checks, runs mixed and near controls sequentially with strict Vulkan synchronization
validation and live DLSS RR (OMM off), and evaluates the numerical gates. Existing output is rejected.
`-ProviderJar` and `-PythonPath` can override local defaults; Python requires NumPy and Matplotlib.
The script restores its diagnostic environment variables on exit.

Finite-water intersection remains opt-in/off by default. Varying-height coarse rest topology, thin or
approximate/unvisited water sources, covered/waterlogged edits, animated travel/temporal appearance,
OMM-on behavior and matched-scene frame time/DLSS cost remain separate requirements. Passing a static
flat-pool comparison does not establish that the underwater dark band or every natural shoreline is
fixed, and no new package has been installed into the player's PCL instance.
