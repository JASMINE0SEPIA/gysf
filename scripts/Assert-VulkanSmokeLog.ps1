[CmdletBinding()]
param([Parameter(Mandatory)][string]$Path,[switch]$RequireNgxClearSync,[switch]$RequireWaterField,[switch]$RequireWaterGuides,[switch]$RequireGpuTimings,[switch]$RequireWaterBoundsCache,[switch]$RequireNearPressure,
      [ValidateRange(1,64)][int]$GuideCaptureCount=4)
$ErrorActionPreference='Stop'
$content=Get-Content -LiteralPath $Path -Raw
$validation='VUID-[A-Za-z0-9_-]+|SYNC-HAZARD|\b(?:WRITE|READ)[_-]AFTER[_-](?:WRITE|READ)\b|hazard detected|Validation Error'
$reports=Select-String -LiteralPath $Path -Pattern $validation
if($reports) {
    throw "Vulkan validation reports in ${Path}: $($reports.Count). First: $($reports[0].Line)"
}
if($RequireNearPressure) {
    $pressure=[regex]::Matches($content,'GYSF_NEAR_PRESSURE: PASS initial=(\d+) minimum=(\d+) recovered=(\d+) instances=(\d+) -> (\d+) -> (\d+) retirementObserved=true ownershipChecks=(\d+)')
    if($pressure.Count -ne 1){throw "Missing unique near-pressure PASS marker in $Path"}
    $p=$pressure[0].Groups
    if([int]$p[1].Value -le 4 -or [int]$p[2].Value -ge [int]$p[1].Value -or [int]$p[3].Value -ne [int]$p[1].Value -or
       [int]$p[5].Value -ge [int]$p[4].Value -or [int]$p[6].Value -le [int]$p[5].Value -or [int]$p[7].Value -lt 30) {
        throw "Near-pressure contraction/recovery/ownership qualification failed in $Path"
    }
} elseif($content -notmatch 'GYSF_(SMOKE|PERF): PASS') {throw "Missing smoke/performance PASS marker in $Path"}
$shutdowns=[regex]::Matches($content,'RT LOD native shutdown: live=(\d+) bytes')
if($shutdowns.Count -eq 0 -or ($shutdowns | Where-Object { [long]$_.Groups[1].Value -ne 0 })) {
    throw "Missing or nonzero native LOD shutdown in $Path"
}
$fieldShutdowns=[regex]::Matches($content,'Water field GPU shutdown: live=(\d+) bytes')
$cacheShutdowns=[regex]::Matches($content,'Water bounds cache shutdown: live=(\d+) bytes, peak=\d+ bytes, inputs=(\d+) bytes, sources=(\d+), pending=(\d+), published=(\d+)')
if($cacheShutdowns | Where-Object {[long]$_.Groups[1].Value -ne 0 -or [long]$_.Groups[2].Value -ne 0 -or [long]$_.Groups[3].Value -ne 0 -or [long]$_.Groups[4].Value -ne 0}) {
    throw "Nonzero water bounds cache shutdown in $Path"
}
if($RequireWaterBoundsCache -and ($cacheShutdowns.Count -eq 0 -or !($cacheShutdowns | Where-Object {[long]$_.Groups[5].Value -gt 0}))) {
    throw "Missing live water bounds cache publication/shutdown qualification in $Path"
}
if($fieldShutdowns | Where-Object {[long]$_.Groups[1].Value -ne 0}) {throw "Nonzero water field shutdown in $Path"}
if($RequireWaterField -and ($fieldShutdowns.Count -eq 0 -or $content -notmatch 'GYSF_WATER_FIELD: PASS')) {
    throw "Missing GPU water field residency/shutdown qualification in $Path"
}
if($RequireNgxClearSync -and ($content -notmatch 'Vulkan clear synchronized: call=' -or $content -notmatch 'DLSS-RR feature created:')) {
    throw "NGX synchronization fix was not exercised by a live RR feature in $Path"
}
$guideShutdowns=[regex]::Matches($content,'Guide readback shutdown: live=(\d+) bytes, peak=\d+ bytes, captures=(\d+)')
if($guideShutdowns | Where-Object {[long]$_.Groups[1].Value -ne 0}) {throw "Nonzero guide readback shutdown in $Path"}
if($RequireWaterGuides -and ($guideShutdowns.Count -ne 1 -or [long]$guideShutdowns[0].Groups[2].Value -ne $GuideCaptureCount -or
        $content -notmatch "GYSF_WATER_GUIDES: PASS phases=$GuideCaptureCount locked=true jitter=0 staticWaves=true" -or
        [regex]::Matches($content,'GYSF_GUIDE_CAPTURE: saved=').Count -ne $GuideCaptureCount)) {
    throw "Missing $GuideCaptureCount-phase GPU guide readback/shutdown qualification in $Path"
}
$timingShutdowns=[regex]::Matches($content,'GYSF_GPU_TIMINGS: complete samples=(\d+) skipped=\d+ livePools=(\d+)')
if($timingShutdowns | Where-Object {[long]$_.Groups[2].Value -ne 0}) {throw "Nonzero GPU timing pools in $Path"}
if($RequireGpuTimings -and ($timingShutdowns.Count -ne 1 -or [long]$timingShutdowns[0].Groups[1].Value -eq 0 -or
        [regex]::Matches($content,'GYSF_GPU_TIMINGS: start timestampBits=').Count -ne 1)) {
    throw "Missing GPU timing sample/lifetime qualification in $Path"
}
Write-Output "Vulkan smoke log accepted: $Path"
