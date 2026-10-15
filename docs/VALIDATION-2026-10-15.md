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
