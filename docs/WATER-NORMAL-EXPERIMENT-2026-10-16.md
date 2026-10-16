# Bounded optical-normal experiment

The candidate replaces finite water's FP16 optical normal with octahedral SNORM16x2 in an
otherwise unused payload lane. Payload size is unchanged. It was tested once against the same
four-view flowing/stepped/covered fixture, keeping the 0.002-block gate unchanged.

The independent Slang/C++ probe passes 4,096 intersection cases and 12,811 runtime/normal
checks; maximum full-sphere codec error is 0.00006050. Consumer checks pass 217 JVM tests and
shader/package checks. Both actual RT games pass Vulkan synchronization and resource retirement.

| Phase | Original maximum transmitted view-Z difference | Candidate maximum difference |
| --- | ---: | ---: |
| above, strength 1 | 0.001800537 | 0.000503540 |
| above, strength 4 | 0.002693176 | 0.000839233 |
| below, strength 1 | 0.001945496 | 0.001945496 |
| below, strength 4 | 0.000656128 | 0.002487183 |

All four candidate first-interface, foreground material and static history checks pass, but
the complete suite still fails at below/strength-4 transmitted depth. The candidate therefore
was **not landed**. Production was restored to `c515ab2`; PCL was untouched. The archived patch,
source hashes, candidate JAR, logs and numerical results are under
`artifacts/2026-10-16/water-normal-candidate`, with raw paired captures under
`artifacts/2026-10-16/water-normal-oct32-final`.

This identifies FP16 normal amplification as contributing to the above-view tail, but does not
establish the source of every remaining discrepancy or prove a visible seam is fixed. Further
numerical tail work is deferred behind practical performance, moving near/LOD boundaries and
the reported DLSS behavior. Finite water remains default-off; complete animated/natural/coarse
water qualification remains part of the project.
