# Non-flat water: forward progress at large ray parameters

This follows the [shared-owner gather](WATER-GATHER-2026-10-16.md). It fixes a separate first-root
failure in known, displaced water. The earlier flat-envelope fix remains necessary: a collapsed
flat interval is solved analytically before entering this loop.

The bounded root solver computes a positive step, with a minimum of 1e-5. At large float32 ray
parameters, `t + step` can round back to `t`; the previous code treated this as exhaustion and
discarded the surface. The fix visits the next representable positive parameter, capped by the
caller's upper limit. Vulkan ray parameters are nonnegative. The existing sign bracket and
bisection then refine the crossing. The 512-iteration cap and residual tolerance are unchanged.

## Discriminating checks

Thirty-two added vertical rays intersect a known fully wet field cell at `(24.75, 24.25)`, with
origins 64 to 8,192 blocks above/below the rest plane and varying wave phase/strength. The
independent expected root is `(waveHeight(origin.xz) - origin.y) / direction.y`; no first-root
solver or packed-field interpolation is used to compute it. The fixture also checks that its
node weight is exactly one. Allowed distance error is two ULPs of that expected parameter.

| Implementation | CPU misses/failures | GPU misses/failures | GPU validation errors |
| --- | ---: | ---: | ---: |
| Previous solver | 14 / 32 | 4 / 32 | 0 |
| Forward-ULP prototype | 0 / 32 | 0 / 32 | 0 |

The retained version adds both ray-limit rejection checks per configuration. The full expanded
4,096-case field suite passes on CPU and GPU with zero validation errors. Maximum distance error
over this expanded suite is 0.000976562 blocks, which is within the per-ray ULP check at the large
parameters; it must not be compared directly to the earlier near-root-only maximum. Maximum work
remains 76. The pre-existing 4,096 patch rays retain 1,346 hits, 2,750 misses, zero exhaustion,
maximum work 21 and total equation work 9,762. All 8,587 runtime checks and 12,960 temporal checks
also pass. Full Gradle `check` and smoke compilation pass, including 207 Java tests.

The local evidence directory is `artifacts/2026-10-16/water-far-roots/`. `probe.slang`,
`host-before.log`, `gpu-before.log` and `before.csv` preserve the failing control. The copied
`prototype-world/` shader tree and corresponding logs preserve the isolated experiment. The
retained fixture is in `src/shaderTest/water_field_probe.slang` and its final GPU readback is under
`final-field/`. Run `scripts/Test-WaterField.ps1` in a fresh output directory with Minecraft stopped.

The archived final JAR `gysf-long-root-final.jar` hashes to
`61656a362570c26f101f63a09c01957d2ae6424a2f8673534a14af2e88187423`; its intersection module hashes
to `f2df8e8b6731b317c4138c80271e3c8b61def2da3da3d1534d8f70cd6b134a31`.
The shipping JAR contains no smoke-only comparator code.

The final game control `perf-long-root-validation` also passes Vulkan and explicitly enabled
synchronization validation, with live DLSS RR and traced synchronized NGX clears. Its 929 measured
frames retain 543 near instances, 1,738 LOD sections and 562,783 known field nodes with no pending
work, upload generation 113 and unchanged scene versions. Native LOD and field live bytes both
return to zero. `Assert-VulkanSmokeLog.ps1 -RequireNgxClearSync` and the scene analyzer accept it.
This single-phase 854x480 / near-4 run is a validation diagnostic (`cadenceQualified=false`),
not a normal-resolution performance comparison. It uses ordinary classpath shaders, with the
optional A/B comparator disabled.

The 3.69% gather result belongs to the recorded shader pair in its own document, before this
forward-progress change. It is not a measured combined speedup for this final JAR. A vertical
analytic oracle plus the existing near/grazing suites does not establish every long grazing
first-root case or natural shoreline topology. Complex shorelines, temporal optical quality,
long-frame behavior and useful full-world performance remain open. Finite water stays default off;
the player's PCL package, settings and worlds are unchanged.

The next [matched water-bounds diagnostic](WATER-BOUNDS-2026-10-16.md) identifies a larger
performance target without changing this checkpoint's shipping geometry.
