[CmdletBinding()]
param([Parameter(Mandatory)][string]$Path,[switch]$RequireNgxClearSync,[switch]$RequireWaterField,[switch]$RequireWaterGuides)
$ErrorActionPreference='Stop'
$content=Get-Content -LiteralPath $Path -Raw
$validation='VUID-[A-Za-z0-9_-]+|SYNC-HAZARD|\b(?:WRITE|READ)[_-]AFTER[_-](?:WRITE|READ)\b|hazard detected|Validation Error'
$reports=Select-String -LiteralPath $Path -Pattern $validation
if($reports) {
    throw "Vulkan validation reports in ${Path}: $($reports.Count). First: $($reports[0].Line)"
}
if($content -notmatch 'GYSF_(SMOKE|PERF): PASS') {throw "Missing smoke/performance PASS marker in $Path"}
$shutdowns=[regex]::Matches($content,'RT LOD native shutdown: live=(\d+) bytes')
if($shutdowns.Count -eq 0 -or ($shutdowns | Where-Object { [long]$_.Groups[1].Value -ne 0 })) {
    throw "Missing or nonzero native LOD shutdown in $Path"
}
$fieldShutdowns=[regex]::Matches($content,'Water field GPU shutdown: live=(\d+) bytes')
if($fieldShutdowns | Where-Object {[long]$_.Groups[1].Value -ne 0}) {throw "Nonzero water field shutdown in $Path"}
if($RequireWaterField -and ($fieldShutdowns.Count -eq 0 -or $content -notmatch 'GYSF_WATER_FIELD: PASS')) {
    throw "Missing GPU water field residency/shutdown qualification in $Path"
}
if($RequireNgxClearSync -and ($content -notmatch 'Vulkan clear synchronized: call=' -or $content -notmatch 'DLSS-RR feature created:')) {
    throw "NGX synchronization fix was not exercised by a live RR feature in $Path"
}
$guideShutdowns=[regex]::Matches($content,'Guide readback shutdown: live=(\d+) bytes, peak=\d+ bytes, captures=(\d+)')
if($guideShutdowns | Where-Object {[long]$_.Groups[1].Value -ne 0}) {throw "Nonzero guide readback shutdown in $Path"}
if($RequireWaterGuides -and ($guideShutdowns.Count -ne 1 -or [long]$guideShutdowns[0].Groups[2].Value -ne 4 -or
        $content -notmatch 'GYSF_WATER_GUIDES: PASS phases=4 locked=true jitter=0 staticWaves=true' -or
        [regex]::Matches($content,'GYSF_GUIDE_CAPTURE: saved=').Count -ne 4)) {
    throw "Missing four-phase GPU guide readback/shutdown qualification in $Path"
}
Write-Output "Vulkan smoke log accepted: $Path"
