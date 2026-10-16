[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testRoot=Join-Path (Split-Path -Parent $PSScriptRoot) 'build/validation-log-tests'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$clean="Synthetic checker fixture`nGYSF_SMOKE: PASS`nRT LOD native shutdown: live=0 bytes"
$guides="$clean`nGYSF_WATER_GUIDES: PASS phases=4 locked=true jitter=0 staticWaves=true"+
    ("`nGYSF_GUIDE_CAPTURE: saved=x"*4)+"`nGuide readback shutdown: live=0 bytes, peak=64 bytes, captures=4"
$cases=@(
    @{Name='clean';Text=$clean;Reject=$false},
    @{Name='ngx-clear-no-vuid';Text="$clean`nvkCmdClearColorImage(): WRITE_AFTER_WRITE hazard detected. nv.ngx.dlssd.resource";Reject=$true},
    @{Name='missing-pass';Text='RT LOD native shutdown: live=0 bytes';Reject=$true},
    @{Name='live-resources';Text='GYSF_SMOKE: PASS';Reject=$true},
    @{Name='mixed-shutdowns';Text="$clean`nRT LOD native shutdown: live=128 bytes";Reject=$true},
    @{Name='field-shutdown';Text="$clean`nWater field GPU shutdown: live=16 bytes";Reject=$true},
    @{Name='field-required';Text=$clean;Reject=$true;Field=$true},
    @{Name='field-qualified';Text="$clean`nGYSF_WATER_FIELD: PASS`nWater field GPU shutdown: live=0 bytes";Reject=$false;Field=$true},
    @{Name='guide-leak';Text="$clean`nGuide readback shutdown: live=64 bytes, peak=64 bytes, captures=4";Reject=$true},
    @{Name='guides-required';Text=$clean;Reject=$true;Guides=$true},
    @{Name='guides-incomplete';Text="$clean`nGuide readback shutdown: live=0 bytes, peak=64 bytes, captures=3";Reject=$true;Guides=$true},
    @{Name='guides-qualified';Text=$guides;Reject=$false;Guides=$true}
)
foreach($case in $cases) {
    $casePath=Join-Path $testRoot ($case.Name+'.log')
    Set-Content -LiteralPath $casePath -Value $case.Text -Encoding utf8
    $rejected=$false
    try { & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path $casePath -RequireWaterField:([bool]$case.Field) -RequireWaterGuides:([bool]$case.Guides) | Out-Null }
    catch {$rejected=$true}
    if($rejected -ne $case.Reject) {throw "Smoke log checker regression: $($case.Name)"}
}
Write-Output "Vulkan smoke log checker: $($cases.Count) cases passed"
