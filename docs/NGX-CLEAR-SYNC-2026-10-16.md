# NGX internal clear synchronization

Environment: Minecraft 26.3 native Vulkan, NGX SDK 310.9.1, RTX 5060 Laptop,
driver 616.92 and Vulkan SDK validation 1.4.341.1. All runs use the isolated
`run-smoke` instance; installed player-instance JARs have not changed.

## Observation and acceptance correction

The validation layer emits two initial RR reports without a `VUID` or
`SYNC-HAZARD` prefix:

```
vkCmdClearColorImage(): WRITE_AFTER_WRITE hazard detected.
... [nv.ngx.dlssd.resource] ... previously written during an image layout transition
... vkCmdPipelineBarrier[... nv.ngx.dlssd.Evaluate]
```

The transition allows shader reads/writes, while the following clear needs a
transfer-write dependency. This occurs in both finite-water-on and off controls,
before placing the water fixture. Earlier VUID-only filtering missed it. Those
runs establish their functional counters/camera checks/resource retirement,
but cannot be called synchronization-clean. Original logs remain unchanged.

[NVIDIA Streamline issue 84](https://github.com/NVIDIA-RTX/Streamline/issues/84)
and a [DLSS SDK integration report](https://forums.developer.nvidia.com/t/validation-errors-using-dlss-vulkan-sdk-due-to-vkcmdclearcolorimage/326493)
contain related internal-clear reports. They are context, not proof that the
local RR integration has exactly the same cause.

## Integration

NGX receives scoped Vulkan proc callbacks. Only `vkCmdClearColorImage` is wrapped;
other commands forward to the real resolver, including chained proc lookups and
unsupported-function null results. The application/driver dispatch tables and
NVIDIA feature DLLs are not modified.

Immediately before forwarding each NGX clear, the wrapper records a memory
dependency from ALL_COMMANDS/MEMORY_WRITE to TRANSFER/TRANSFER_WRITE. This orders
preceding internal layout transitions/writes before that clear. An application
barrier before Evaluate would precede the internal transition and cannot order
that later transition against the clear within Evaluate.

`NgxRuntime` supplies the real instance resolver as well as the device resolver.
The device-only experiment initialized RR but did not intercept its internal
clears; `NGXSHIM_SYNC_TRACE=1` exposed that non-execution, and strict acceptance
rejected the log. The shim retains its existing nullable instance-resolver ABI.
The first prototype accidentally required it and failed with InvalidParameter;
the nullable path is now covered by the native test.

Two native CTests pass: preset transitions and clear synchronization/forwarding.
The latter checks the actual barrier stage/access masks, ordering before clear,
unchanged clear arguments, instance/device/chained resolution, unrelated lookup
forwarding, unsupported lookup and nullable instance-resolver behavior.

## Log qualification

`Assert-VulkanSmokeLog.ps1` rejects bare READ/WRITE_AFTER_* hazards as well as
VUID/SYNC-HAZARD/Validation Error messages. It requires the smoke/performance PASS
marker and every native LOD shutdown to report zero live bytes. Five independent
checker fixtures include the exact prefix-free NGX form and mixed zero/nonzero
shutdowns. Windows CI runs the checker regression alongside native CTest.
`Build-26.3.ps1 -Smoke/-Joint` records and checks its game log.

The optional `-RequireNgxClearSync` additionally requires a live RR feature and
an actual synchronized-clear trace. Merely falling back to another renderer or
building the wrapper is not sufficient. The unmodified vendor DLL hash remains
`4BC7EA5FCB2F32CF86BC2CB072E8D2860914A0BE511CBDCB2FC206C1D9D83B80`.
Per-frame overhead and image quality are separate acceptance work.

The first live instance-resolver control (`artifacts/2026-10-16/ngx-clear-sync/instance-control/`)
passed strict log acceptance: 5,298 RT frames, a live 427x240 to 854x480 RR feature,
two actual synchronized clears, no matching validation reports, and zero native
LOD live bytes at shutdown. The loaded shim SHA-256 was
`51D1D46073D85297D664CFEBB68FB48540996CD0236AE3B4C8F05B52CE5EFCF7`.
The combined water-camera/lifecycle qualification is recorded separately in
[the finite-water runtime record](WATER-RUNTIME-2026-10-15.md).
That combined qualification subsequently passed all three strict controls
(five camera phases, finite-on moving/pressure lifecycle, finite-off lifecycle),
with 52,420 RT frames and zero native LOD live bytes at each shutdown. The
original NVIDIA feature DLL remains unchanged; the repair is in this shim and
the proc callbacks supplied at NGX initialization.
