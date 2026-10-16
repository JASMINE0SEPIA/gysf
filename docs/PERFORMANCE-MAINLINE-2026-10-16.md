# Practical performance and next priorities

The main acceptance targets are playable frame cadence, correct near/LOD boundaries during
movement, and working DLSS with predictable cost. Water numerical tails remain recorded in
[the bounded experiment](WATER-NORMAL-EXPERIMENT-2026-10-16.md), with the complete quality goal
retained. They no longer block work on these player-facing targets.

## Current default renderer baseline

Production `c515ab2`, provider `15ff198`, RTX 5060 Laptop GPU: output 1792x1120, actual trace
896x560, DLSS Performance, near radius 16, 1 spp/4 bounces, OMM on, finite procedural water
off (the default). The isolated natural-terrain scene uses a fixed camera and frozen time/waves.
After 90 seconds of initial warmup plus actual scene settling, each phase settles eight seconds
and measures twenty. No validation, GPU timing or CPU statistics instrumentation is active.

| Phase | Requested RR model | Mean frame ms | FPS |
| --- | --- | ---: | ---: |
| 0 | Default | 20.0887 | 49.78 |
| 1 | F | 18.9941 | 52.65 |
| 2 | Default | 19.9116 | 50.22 |

All measured frames retain 6,455 near instances and 2,006 LOD sections with unchanged geometry
versions and no pending builds. Driver-reported usage stays 3,536,400,384 bytes against a
7,477,395,456-byte budget. Native LOD and bounds-cache allocations retire to zero. The analyzer
accepts scene/render invariants and marks `cadenceQualified=true`.

This is a current baseline, not a measured speedup over the player's installed package, and it
does not reproduce the player's save or prove moving-camera image quality. One F phase does
not establish a consistent model advantage. Both tested requests operate at the same trace size.

Evidence: `run-smoke/performance-smoke/perf-default-mainline`; local archive
`artifacts/2026-10-16/default-mainline-baseline`. Gradle launches compiled classpath resources,
not `build/libs` JARs. All 32 runtime SPIR-V modules were compared with the preserved `c515ab2`
checkpoint JAR (SHA-256 `ff3c0fa7cc5c34794bcfdba24b22174c220979e0c67700fc4d75f9de3d6db839`)
and match exactly. The archive records these actual resource hashes.

## Exact opaque-coverage candidate

The existing Voxy any-hit path samples the atlas even for completely opaque ordinary surfaces,
then closest-hit samples it again for shading. Raster's 90% coverage heuristic is insufficient
to skip the first lookup: a mostly solid texture can still contain a hole.

Provider `cf3e16a3` exports a separate exact full-tile alpha-255 bit (face bit 26). The consumer
copies this promise to an ordinary solid/emissive primitive flag and accepts that hit after the
existing readiness/atlas checks. Water and glass stay on their optical paths; old producers and
unproven coverage retain the existing lookup. Provider checks pass 71 tests; consumer checks
pass 218, including old-provider and water/glass separation.

Smoke-only pipeline comparison now supports any-hit alone, including finite-water-off mode.
Both pipelines share all scene/descriptors/material data and other shader modules. Actual SBT
and executable selection is observed per measured frame and recorded by SHA-256. The analyzer
requires both variants, one candidate identity, matching settings and a stable positive count of
exact-alpha tagged quads. No performance gain is accepted before this comparison is qualified.

## Validation tool defect found during the comparison

The first instrumented A/B (`perf-opaque-alpha-validation`) is rejected by the strict Vulkan log
gate. Installed validation layer 1.4.341 reports VUID 10904 for a zero micromap index address,
although `VK_INDEX_TYPE_NONE_KHR` requires zero. The renderer already writes zero.

This is independently visible in the official
[1.4.341 validation source](https://github.com/KhronosGroup/Vulkan-ValidationLayers/blob/vulkan-sdk-1.4.341.0/layers/core_checks/cc_ray_tracing.cpp#L660):
the error branch tests `!deviceAddress`. The official
[1.4.363 source](https://github.com/KhronosGroup/Vulkan-ValidationLayers/blob/vulkan-sdk-1.4.363.0/layers/core_checks/cc_ray_tracing.cpp#L614)
corrects it to `deviceAddress != 0`, matching the
[Vulkan structure contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkAccelerationStructureTrianglesOpacityMicromapEXT.html).

That run confirms immutable scene and shader/SBT order, 2,909,565 tagged packed quads, and zero
remaining alternate pipelines/timing pools/native LOD bytes. Its diagnostic stage timings vary
enough across phases that no stable gain is claimed. The log rejection is retained; no filter
exception was added. Qualification will use a corrected validation layer scoped to the isolated
test process, followed by uninstrumented cadence if the candidate is useful. PCL is untouched.

The corrected layer is now verified in the actual Java process: SDK 1.4.363, validation DLL
SHA-256 `2acc317ef880f73a9862f23a964694c03f531b866fab6d70f1412fcbae60caac`.
It was extracted into `.tools/vvl-10904/layer/Bin` from the official SDK installer, SHA-256
`94a82d378f7a5e3e54c9db7d2fb7016af136e14ac0a18dbf0f2f67a36352d141`, checked against
LunarG's public file manifest. Only process-local `VK_LAYER_PATH` is set; no system SDK or
player installation was changed. Compilation continues using pinned Slang 2026.18.

`perf-opaque-alpha-vvl363` completes the four-phase A/B with zero validation reports, live RR
clear synchronization, and zero remaining alternate pipelines/timing pools/native LOD bytes.
Scene/settings/shader order are accepted. Instrumented total GPU intervals are 16.367, 16.367,
16.347 and 16.565 ms (original/candidate/candidate/original); their small difference is not
treated as an established FPS gain. The analyzer correctly keeps `cadenceQualified=false`.
The malformed-shader-identity and missing-exact-coverage controls are rejected by the analyzer;
the existing default baseline schema remains accepted.

## Uninstrumented cadence and lifecycle result

`perf-opaque-alpha-cadence` repeats original/candidate/candidate/original with the same scene,
1792x1120 output and 896x560 tracing, near 16, Default/Performance, OMM on, 1 spp/4 bounces and
default finite-water-off. Initial warmup is extended to 180 seconds, then eight-second settles
and thirty-second samples. No validation, CPU statistics or GPU timing instrumentation is active.

| Variant | Frames | Mean ms | FPS |
| --- | ---: | ---: | ---: |
| original | 1,525 | 19.6602 | 50.86 |
| candidate | 1,554 | 19.2976 | 51.82 |
| candidate | 1,561 | 19.2055 | 52.07 |
| original | 1,557 | 19.2555 | 51.93 |

The 6,197 frames pass immutable geometry/settings/shader-order checks and the analyzer marks
`cadenceQualified=true`. Weighted means are 19.4557 ms original and 19.2514 ms candidate, a
roughly 1.05% difference. The original phases themselves differ by about 0.405 ms, larger than
the grouped difference of 0.204 ms. **A stable FPS improvement is not established.** This bounded
experiment is complete; no further iteration on this micro-optimization precedes the matching
near-32/player-settings test. The exact coverage contract removes redundant texture work without
changing the accepted surface: full alpha is guaranteed, and ownership/optical paths stay intact.

The following OMM-enabled full lifecycle run (`opaque-alpha-full-smoke.log`) completes 23,408
frames, 392 coverage checks, textured fixtures, resource reload and dimension changes. Native
LOD shutdown is zero (peak 667,548,800 bytes); the bounds cache is zero. With the corrected
validation layer, the strict Vulkan/NGX checker accepts the log with no suppressed reports.

The tested candidate pair is archived under `artifacts/2026-10-16/opaque-alpha-checkpoint`:

| Artifact | SHA-256 |
| --- | --- |
| `gysf-0.7.0-dev+mc26.3.jar` | `2278567da666e288c8a8cfa8bb78a040a1c71fb99c1e78f2a54f975b7f2d0202` |
| `voxy-vulkan-0.2.20-26.3-vulkan.12-dev-cf3e16a.jar` | `01a6ae1a4246b7dd2704dc8213117d3cecbc2341e811511358240958ce6b559e` |

Reproduction with the staged SDKs/Slang and no other Minecraft process:

```powershell
$env:VK_LAYER_PATH=(Resolve-Path '.tools/vvl-10904/layer/Bin').Path
$env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true'
$env:NGXSHIM_SYNC_TRACE='1'
.\scripts\Test-Performance.ps1 -Label fresh-validation -RenderDistance 16 -Width 1792 -Height 1120 `
  -InitialSeconds 60 -SettleSeconds 5 -MeasureSeconds 10 -EnableOmm -ValidateVulkan -GpuTimings `
  -Presets '0,0,0,0' -AnyHitShaderBaseline artifacts/2026-10-16/default-mainline-baseline/world.rahit.spv -AnyHitShaderSequence '0,1,1,0'
.\scripts\Test-Performance.ps1 -Label fresh-cadence -RenderDistance 16 -Width 1792 -Height 1120 `
  -InitialSeconds 180 -SettleSeconds 8 -MeasureSeconds 30 -EnableOmm `
  -Presets '0,0,0,0' -AnyHitShaderBaseline artifacts/2026-10-16/default-mainline-baseline/world.rahit.spv -AnyHitShaderSequence '0,1,1,0'
```

The original shader is preserved in the default-mainline archive with SHA-256
`1d1c5a6c6da29c5741618e1e9f895c6bf59db0853971a2d5895c0c7a658b2517`; the candidate is
`d727edd74d6262bbce53f187b306cb915e01d11dd237fdf1b79b5345c2a6f6ef`. Analyze validation with
`--validation --gpu gpu.csv`; analyze cadence without instrumentation flags. No PCL files changed.

## Read-only player-install refresh

The existing PCL profile is now `versions/26.3`; the earlier `26.3-Fabric 0.19.5` directory no
longer exists. Its metadata still uses Fabric Loader 0.19.5. Mods remain gysf 0.6.2 and provider
Vulkan.8; Fabric API is 0.163.0+26.3 (development currently pins 0.161.0+26.3). Latest player log
and config were written October 15, so they describe the earlier played package.

The saved options request near distance 32, simulation distance 24, vsync off and max FPS 260.
RT settings are 1 spp/4 bounces, OMM level 4, seven-component strength/speed-1 water, RR F at
Performance, frame generation off and SHaRC off. Therefore the near-16 baseline above cannot
stand in for that user's load. A matching near-32/F/current-Fabric-API run is required before
calling the practical performance problem solved. No player files were changed by this audit.
