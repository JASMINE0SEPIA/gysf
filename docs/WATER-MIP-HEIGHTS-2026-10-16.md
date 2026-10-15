# Cross-LOD source water elevation

The provider mipper previously discarded the height of the chosen source voxel.
Its fluid mesher then multiplied the block state's own fractional height by the
LOD scale. A source top at Y=8/9 became Y=128/9 at level four: a reproduced
13.333252-block step. Four UNORM16 corners did not recover the missing source
elevation.

Vooxxy Vulkan 9 carries source surface elevations through L1-L4 in previously
unused mapping bits and exports the corrected normalized heights through the
existing API 6. gysf's production decoder and shader ABI need no change.
Unchanged fine water now also refreshes old parent data on reingestion; unknown,
unvisited old coarse data retains its explicit fallback. No player database is
deleted or rewritten by the test harness.

`FluidMipSmokeProbe` adds an opt-in, separate `gysf-fluid-mip-26.3` development
world. A frozen 96x96 pool has a source top at Y=176. The probe runs the actual
provider mesher on all five LOD levels after live chunk ingestion, then uses
`RtVoxyMesh.corners` to inspect the same vertices used by RT builds. It neither
changes the renderer's selected hierarchy nor installs its inspected meshes in
the TLAS. Thus it qualifies the geometry conversion, not five independently
forced GPU raster/depth images.

Initial Vulkan/synchronization-validation run (OMM off, finite water on, live
DLSS RR and actual synchronized NGX clears):

| LOD | Expected world Y | Converted world Y | Error, blocks |
| --- | ---: | ---: | ---: |
| 0 | 176.88889 | 176.88889 | 0 |
| 1 | 176.88889 | 176.88890 | 0.00001526 |
| 2 | 176.88889 | 176.88887 | 0.00001526 |
| 3 | 176.88889 | 176.88893 | 0.00004578 |
| 4 | 176.88889 | 176.88893 | 0.00004578 |

The run completes 9,100 RT frames, with 145 near and 186 LOD water children,
331 water instances packed at peak, no memory deferrals, native LOD peak
130,943,184 bytes and zero live bytes at shutdown. It passes
`Assert-VulkanSmokeLog.ps1 -RequireNgxClearSync`. Logs and captures are under
`artifacts/2026-10-16/fluid-mip/`. This first run used the source worktree before
its commit; its provider JAR SHA-256 is
`4DF7F56088A10D2EB56A62A7B8C246EA1C372FC85469080B988FE8310AF8E8D2`.
The final source/artifact identities belong in the evidence checkpoint.

Provider CPU tests cover 230 height cases across all LODs, all source vertical
offsets, flowing states and signed origins, plus field/serializer preservation,
fluid-type separation, highest-source selection, unknown old children and
unchanged-source cache upgrades/removal. All 45 provider tests pass.

This removes a vertical scaling error in homogeneous bodies. Horizontal shore
aggregation, three-bit legacy quad precision, the common shoreline wave
envelope, GPU guide/depth readback and matched-scene performance remain open.
The finite-water path stays off by default and the PCL instance is unchanged.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'
$env:VULKAN_SDK='C:\VulkanSDK\1.4.341.1'
$env:DLSS_SDK=(Resolve-Path '.tools/DLSS-310.9.1').Path
$env:JAVA_TOOL_OPTIONS='-Dgysf.rt.finiteWater=true -Dcaustica.rt.omm=false -Dgysf.smoke.cleanWater=true -Dgysf.smoke.waterCompareOnly=true -Dgysf.smoke.fluidMip=true -Dgysf.smoke.requirePreciseFluids=true -Dgysf.voxy.validateCoverage=true -Dgysf.smoke.waveStrength=1'
$env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true'
$env:NGXSHIM_SYNC_TRACE='1'
.\scripts\Build-26.3.ps1 -Joint -ValidateVulkan
```
