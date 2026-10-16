# Shared shoreline field foundation

Vooxxy Vulkan 10 exports optional water-domain capability 1 while preserving
mesh API 6. `VoxyWaterDomain` copies its immutable tile data across the optional
reflection boundary, validates payloads/revisions, and keeps provider types out
of the renderer's constant pool. Unsupported providers retain the existing
geometry path.

`RtWaterShoreField` is the CPU reference for the GPU binding. It samples
uniquely owned nodes on a world-space unit lattice and derives weights from the
available excursion and the current displacement bound. Integer world anchors
preserve fractional positions through rebases and near the world border.
Smoothstep interpolation operates inside that unit cell, independent of the
primitive's width, diagonal or LOD.

The coarse-control test distinguishes this policy from stretching four corner
weights across a 16-block water face. Other tests cover border ownership,
ceiling limits, gradients against finite differences, unknown data, immutable
copies, epoch resets and malformed/regressing exports. No test substitutes an
identical per-primitive interpolation for the common world field.

The opt-in game probe requires `gysf.smoke.fluidMip=true` and
`gysf.smoke.waterDomain=true`, together with the existing isolated clean-water
comparison settings. It uses `gysf-fluid-mip-26.3`, never a PCL save. The initial
run completes three source/export phases: open clearance 3, glass-ceiling
clearance 1, and restored clearance 3. Shore/covered nodes are zero, unvisited
source nodes remain explicitly unknown, and previous copies remain immutable.
Both terrain anchors produce identical sampled weights and gradients.

That initial run completes 8,383 RT frames with strict Vulkan/synchronization
log acceptance, live DLSS RR and actual synchronized NGX clears. Native LOD
peak is 133,114,960 bytes and exit live bytes are zero. It used the pre-commit
worktree; final source/artifact identities and logs belong under
`artifacts/2026-10-16/water-domain/`. These figures qualify the source/export
path running alongside RT, not a rendered shoreline fix or a performance gain.

The subsequent [GPU integration](WATER-FIELD-GPU-2026-10-16.md) binds the common
field for intersection, normals and previous-frame motion. The original
source/export-only results above remain limited to their original scope.
Edited and mixed-LOD optical controls, coarse rest topology, GPU guide/depth
images and matched-scene frame time remain open. New water geometry remains
off by default, and the player instance is unchanged.
