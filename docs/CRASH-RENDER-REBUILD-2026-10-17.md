# Render-distance rebuild crash, 01:05

The player's 0.7.2 report records `VK_ERROR_DEVICE_LOST` after changing near distance from 6 to 32
at 01:05:12. Voxy destroys and recreates its core/512 MiB model atlas at that point. The device
fault includes an invalid read; the later terrain-worker submission/semaphore exceptions are
consequences of the lost device, not independent section-coordinate failures. The last memory
sample has 119 MiB native LOD allocations and roughly 4.8 GiB allocator headroom, so this report
does not establish a VRAM exhaustion failure.

The provider was polled only at START_CLIENT_TICK. A vanilla render invalidation can run after
that tick and before the next trace, leaving the consumer's cached scene/borrowed atlas tied to
the destroyed core. The return-route test in 0.7.2 did not exercise this interval, and earlier
lifecycle tests preceded the latest changes. Passing those routes did not qualify this trigger.

The isolated regression copies `Nuovo mondo`, configuration and resource packs from the player's
profile. It keeps the crash position, near 6, 6,041 MiB heap, DLSS-RR, 3x frame generation and
opacity micromaps enabled. Only the development copy omits Dynamic FPS to avoid background
throttling; the original profile is never used as a writable test directory. A render hook runs
`LevelExtractor.allChanged()` after tick polling. An inspection-only probe compares the cached
consumer epoch against the actual core's private session before tracing; it never polls or fixes
the provider itself.

On unchanged 0.7.2 code, the first 6 -> 32 rebuild deterministically fails the boundary assertion:
`GYSF_STALE_PROVIDER: cachedEpoch=1 actualEpoch=-9223372036854775808`. The new core has no polled
session yet, while gysf still owns the old scene. The guard stops before dereferencing that stale
scene on the GPU. This reproduces the lifetime violation without deliberately inducing another
device loss; it does not independently resolve the original GPU fault address to an atlas texel.

0.7.3 polls the provider at each RT frame before reserving graphics-use tokens or recording RT
commands. Provider uploads may submit the Minecraft encoder, so polling inside an already-recorded
RT command buffer would be unsafe. Epoch/world invalidation also precedes draining completed
work and restoring cached fine sources, preventing old-model results from publishing first.

The player's log separately shows continuous nearby-refinement replans about every half second,
often before useful detail publishes. A plan now receives at least two seconds and must publish
a checkpoint containing completed GPU build work before nearby refinement can interrupt it.
Camera-cell changes and explicit provider/world invalidations still cancel obsolete work. This
is a forward-progress correction, not evidence that replanning caused the device loss.

The fixed replay passes four explicit after-tick rebuilds, including two with the game paused:
6 -> 32 -> 6 -> 32 plus a same-distance F3+A-style rebuild. It renders 4,130 frames and performs
4,128 live provider-boundary comparisons, 109 near ownership checks and 69,748 finer-source
checks. No stale-provider assertion, VUID or synchronization report occurs under validation
layer 1.4.363. DLSS-RR, NGX clear synchronization, 3x frame generation and opacity micromaps are
actually active. LOD native allocations return to zero (168,925,344-byte peak, zero deferrals),
as do the water field/bounds cache allocations. The requested horizon is not temporarily capped.

All 244 consumer tests and package/ABI checks pass. The 0.7.3 JAR matches the 395 runtime classes,
32 SPIR-V modules and mixin configuration used in the replay; the Voxy Vulkan.13 provider is
unchanged. This roughly 110-second world replay qualifies the repeated invalidation window, not
an independently reproduced 21-minute device-loss session or every possible device-loss cause.

Local evidence is retained in `artifacts/2026-10-17/crash-0105`: the original report/log and protected
file inventory, the guarded failing baseline, the fixed runtime log, packaging verification and
deployment receipt. Device-loss semantics are described in the
[Vulkan specification](https://docs.vulkan.org/spec/latest/chapters/devsandqueues.html#devsandqueues-lost-device).
