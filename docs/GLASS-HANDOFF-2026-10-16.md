# Near/LOD glass material handoff

The frozen flowing/stepped water fixture exposed a production material defect: plain glass
was a refractive volume in the near renderer but an opaque/cutout surface in the Voxy RT mesh.
This correction uses provider `15ff198e3d5de3457f0d2372670eff432358e79d` (Vulkan.11).
Finite procedural water remains default-off. PCL files were not changed.

## Cause and correction

The provider's raster baker optimizes binary-alpha translucent textures to SOLID/CUTOUT.
It now preserves the authored optical classification in optional model flag bit 32, independent
of raster bit 4. Models containing opaque geometry are not promoted wholesale. Optical meaning
is part of the texture-deduplication key, preventing opaque and transparent models with identical
RGBA bakes from sharing incorrect metadata. Older producers retain their bit-4 fallback.

The consumer preserves glass faces across transparent texels: alpha controls optical tint rather
than opening holes in a volume. Textured Voxy glass uses the near path's texel filter and material
parameters; the legacy colour proxy retains its separate approximation. Near/LOD glass shares a
0.0002-block inward inset. Integer section offsets are subtracted before adding local FP32
positions, avoiding lost fractional geometry at large world coordinates. A regression test
checks identical packed positions at anchors 0 and +/-30 million blocks.

This qualifies the tested plain-glass interface and ordinary material guides. It does not prove
complete LabPBR, ice, shadow transmission, final radiance or every translucent model matches.

## Actual Vulkan RT comparison

Run `scripts/Test-WaterGuides.ps1 -Varied -OutputDir <fresh-directory>` with the configured
JDK25/Vulkan/DLSS environment. Its own `run-smoke/gysf-varied-water-26.3` world contains frozen
flow levels 0..7, a one-block step, stone cover and two glass covers. A separate all-near run
is the reference. Waves are static, strengths 1 and 4, cameras above and below the surface,
OMM off, live RR on. Particles are disabled only for this fixed-geometry diagnostic so random
ambient particles cannot contaminate the comparison. This is not a performance workload.

Local raw readbacks: `artifacts/2026-10-16/water-varied-first` (before) and
`artifacts/2026-10-16/glass-handoff-first` (after). The initial before run had particles enabled;
its below-water mismatch counts include particle noise and are not used as a controlled
before/after material score. Above-water glass classification is directly observed in both.

| Phase | Near-reference glass pixels | Mixed glass before | Mixed glass after | After foreground material mismatches | After missing interior water pixels |
| --- | ---: | ---: | ---: | ---: | ---: |
| above, strength 1 | 1,586 | 0 | 1,586 | 0 | 0 |
| above, strength 4 | 1,580 | 0 | 1,580 | 0 | 0 |
| below, strength 1 | 6 | 0 | 6 | 0 | 0 |
| below, strength 4 | 32 | 0 | 32 | 0 | 0 |

The after run also observes 140/100 step-side pixels and 889 stone-cover pixels in the two above
views, with both water elevations represented. Non-water position differences are at most
0.00004645 blocks; non-water normal differences are zero. All four ordinary material guide,
foreground, and static normal/motion history gates pass.

## Remaining strict water gate

The complete varied-water suite **still fails**. The unchanged 0.002-block limit includes
transmitted view depth, not only the first water interface:

| Phase | Maximum first-interface position difference (blocks) | Maximum interface normal vector difference | Maximum transmitted view-Z difference (blocks) | Full interface/depth gate |
| --- | ---: | ---: | ---: | --- |
| above, strength 1 | 0.000125190 | 0.000419993 | 0.001800537 | pass |
| above, strength 4 | 0.000406386 | 0.000422815 | 0.002693176 | fail |
| below, strength 1 | 0.001963614 | 0.000227350 | 0.001945496 | pass |
| below, strength 4 | 0.000657634 | 0.000298258 | 0.000656128 | pass |

Six LOD water pixels exceed the depth limit in the high-strength above view. At the worst pixel
(206,130), the first position differs by 0.0000076294 blocks, but the normal differs by
0.0003884555. The complete values at that pixel, including its 0.002693176 depth difference,
are identical before and after this material correction (`depth-before-after.json`). Source
height quantization amplified by half-precision normal storage is a hypothesis to investigate,
not an established cause. Thresholds were not relaxed and no `pass.json` was written.

Moving cameras, animated water history, actual selected coarse-LOD side/top transitions and
natural terrain still need runtime qualification before enabling finite water by default.

## Other checks and artifact identity

- Consumer: 217 JVM tests pass, shader/native ABI/package checks and smoke compilation pass
  (`glass-rebase-build.log`). Provider: 69 tests and backend isolation/build pass.
- Both paired RT games pass Vulkan synchronization/NGX clear checks and retire native LOD,
  water fields, bounds caches and guide readbacks to zero. The numerical gate above remains failed.
- Default finite-water-off mode passes the full smoke lifecycle with the textured fixture,
  resource reload and dimension changes: 26,875 frames, 422 coverage checks, 1,751 maximum
  provider meshes and 2,049 maximum RT LOD sections (`glass-default-smoke.log`). Native LOD
  shutdown is zero; Vulkan synchronization and live RR clear checks pass.
- The previous source-validation commit `1dbfa0f` also passed
  [cloud run 38950450520](https://github.com/JASMINE0SEPIA/gysf/actions/runs/38950450520).

Built pair, archived separately from PCL:

| Artifact | SHA-256 |
| --- | --- |
| `gysf-0.7.0-dev+mc26.3.jar` | `FF3C0FA7CC5C34794BCFDBA24B22174C220979E0C67700FC4D75F9DE3D6DB839` |
| `voxy-vulkan-0.2.20-26.3-vulkan.11-dev-15ff198.jar` | `A896125620054D5EAE84AA2A12D2065550DCDE549D56F6E8FEA1A4E8BE91A89A` |

Local archive: `artifacts/2026-10-16/glass-handoff-checkpoint`. Artifacts and raw readbacks are
ignored by Git; committed sources and scripts reproduce the checks. No new FPS measurement or
player visual acceptance is claimed for this correction.
