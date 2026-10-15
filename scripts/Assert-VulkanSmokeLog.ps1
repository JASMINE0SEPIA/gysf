[CmdletBinding()]
param([Parameter(Mandatory)][string]$Path,[switch]$RequireNgxClearSync)
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
if($RequireNgxClearSync -and ($content -notmatch 'Vulkan clear synchronized: call=' -or $content -notmatch 'DLSS-RR feature created:')) {
    throw "NGX synchronization fix was not exercised by a live RR feature in $Path"
}
Write-Output "Vulkan smoke log accepted: $Path"
