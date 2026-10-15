# GPU memory admission and recovery

gysf 0.3.0 optionally enables `VK_EXT_memory_budget` and exposes a sampled
budget for the largest device-local heap. The driver reports estimated usage
for the entire process, including Minecraft, Voxy, gysf and vendor runtimes.
Separate VMA allocator usage values must not be added to that estimate.
RT allocator allocation bytes and provider Java-owned allocation bytes are
reported separately for diagnosis. The provider counter excludes objects whose
Java ownership has already been released while native destruction is deferred.
An unavailable legacy-provider counter is reported as -1 rather than zero.

The implementation follows the [Vulkan memory-budget contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceMemoryBudgetPropertiesEXT.html)
and [VMA budget guidance](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/staying_within_budget.html).
The RT allocator enables the matching VMA flag when supported and advances
VMA's frame index. Direct driver readings are sampled every 250 ms. Without
the extension, telemetry is explicitly marked as estimated; only the local
LOD quota is enforced, rather than claiming process-wide headroom is known.

## LOD working set

Each textured LOD buffer reserves a thread-safe allocation ticket before VMA
allocation, then commits VMA's actual allocation size. Tickets include positions,
indices, UV/material buffers, upload staging, BLAS backing, build scratch and
compaction destinations. They survive publication changes and are released
only after native buffer destruction. This counts unpublished replacement
branches, old geometry awaiting graphics retirement and compaction overlap.
The borrowed atlas is covered by the driver process estimate, not charged again
as consumer-owned geometry.

The default native LOD working quota is one quarter of the driver heap budget,
clamped to 256..2048 MiB. `-Dgysf.voxy.memoryBudgetMiB=<1..4096>` provides a
development override. This quota includes host staging conservatively and is
not a promise that existing allocations instantly fall below a newly lowered
limit. Small section-table allocations remain allowed so a smaller published
selection can retire old geometry. They are still counted and retired normally.

Before device-preferred LOD allocations, the consumer additionally preserves
10% of the reported heap budget (256..1024 MiB) for near rendering and other
resources. Growth since the cached driver snapshot is charged against that
headroom. Device-preferred buffer allocations also use VMA's `WITHIN_BUDGET` flag; a native LOD
device-memory rejection becomes a deferral rather than disabling the renderer.

The 0.7 development path distinguishes live allocations from unused storage in
the current RT allocator's reserved device blocks. It credits half of that unused
storage toward this allocator's admission/recovery headroom, leaving the rest for
fragmentation and memory-type compatibility. Reusing it need not grow process
heap usage. This is not additional physical memory or credit for Voxy's separate
allocator. Physical block growth still goes through VMA's budget checks. Raw
process usage, live RT allocation bytes and unused reserved storage are all
logged separately.

## Distance control

Pressure cancels unpublished work, keeps the published fallback and halves a
temporary provider distance limit down to 128 blocks. New plans can publish
removal of out-of-window sources before allocating replacements. GPU retirement
still controls when their native bytes become available.

After eight quiet seconds and comfortable headroom, distance recovers gradually
at intervals of three seconds. Returning to zero clears the temporary limit
and restores the original request. Resource/world teardown also clears the
limit. The consumer uses the optional API 5 bridge method
`setExternalDistanceLimit`; it never writes the user's Voxy distance setting.
Older providers without this method can defer allocation, but cannot provide
automatic distance reduction. Use the paired Vulkan 6 provider for recovery.

In the 0.7 development path, driver pressure can also reduce the transient near
window while Voxy is active. The requested/saved distance remains unchanged.
The radius contracts by a quarter at most every two seconds, with a four-chunk
floor (or the smaller user request). New missing sections pause under pressure;
edits may still replace existing geometry. The actual published-readiness mask,
including confirmed-empty sections, continues to determine LOD ownership.
Ten seconds of comfortable allocator headroom permits gradual recovery, only
after both near and LOD build queues have settled. A partially built replacement
must not trigger growth that evicts it again when its remaining allocations arrive.

Rejected LOD allocations also gate later refresh admission before another CPU
plan or BLAS build. The retained request is checked without creating a ticket or
counting another deferral. The captured pressure and retained-pool investigations
are recorded in [PERFORMANCE-2026-10-15.md](PERFORMANCE-2026-10-15.md).

This is admission control for textured RT LOD resources, with a shared-process
headroom signal. It is not a hard cap on every allocation in Minecraft, every
memory heap, vendor SDKs, Java mesh caches or unrelated mods. Sudden driver or
other-process changes can still reject allocations. Cross-device and extended
travel qualification remain separate from a successful development smoke run.

## Reproduction

The CPU suite tests actual-size accounting, rollback, native retirement,
concurrent reservations, cached driver readings, missing extension fallback,
reclamation metadata, distance reduction and delayed recovery.

`-Dgysf.smoke.memoryPressure=true` lowers only the isolated smoke process's LOD
quota to 1 MiB at world tick 400 and restores its original value at tick 600.
The harness requires allocation deferrals, a reduced provider distance, at
least 20 ticks with a published fallback, and full distance recovery before
resource reload at tick 1000. `-Dgysf.smoke.requireDriverBudget=true` additionally
requires a real driver budget on supported test hardware. These flags never
touch PCL files or player worlds. GPU results are recorded in
[the validation log](VALIDATION-2026-10-15.md).
`-Dgysf.smoke.noMemoryDeferrals=true` requires no allocation deferral during an
independent default-budget run.

After building the sibling provider, the injected-quota command is:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dcaustica.rt.omm=false -Dgysf.smoke.textureFixture=true -Dgysf.smoke.visualControl=true -Dgysf.smoke.motion=true -Dgysf.voxy.validateCoverage=true -Dgysf.smoke.requireDriverBudget=true -Dgysf.smoke.memoryPressure=true'
$env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true'
.\scripts\Build-26.3.ps1 -Joint -ValidateVulkan
```

For the independent baseline replace `-Dgysf.smoke.memoryPressure=true` with
`-Dgysf.smoke.noMemoryDeferrals=true`. OMM is disabled only for the installed
SDK checker defect described in the validation record; DLSS-RR remains enabled.
