[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testRoot=Join-Path (Split-Path -Parent $PSScriptRoot) 'build/validation-log-tests'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$clean="Synthetic checker fixture`nGYSF_SMOKE: PASS`nRT LOD native shutdown: live=0 bytes"
$cases=@(
    @{Name='clean';Text=$clean;Reject=$false},
    @{Name='ngx-clear-no-vuid';Text="$clean`nvkCmdClearColorImage(): WRITE_AFTER_WRITE hazard detected. nv.ngx.dlssd.resource";Reject=$true},
    @{Name='missing-pass';Text='RT LOD native shutdown: live=0 bytes';Reject=$true},
    @{Name='live-resources';Text='GYSF_SMOKE: PASS';Reject=$true},
    @{Name='mixed-shutdowns';Text="$clean`nRT LOD native shutdown: live=128 bytes";Reject=$true},
    @{Name='field-shutdown';Text="$clean`nWater field GPU shutdown: live=16 bytes";Reject=$true},
    @{Name='field-required';Text=$clean;Reject=$true;Field=$true},
    @{Name='field-qualified';Text="$clean`nGYSF_WATER_FIELD: PASS`nWater field GPU shutdown: live=0 bytes";Reject=$false;Field=$true}
)
foreach($case in $cases) {
    $casePath=Join-Path $testRoot ($case.Name+'.log')
    Set-Content -LiteralPath $casePath -Value $case.Text -Encoding utf8
    $rejected=$false
    try { & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path $casePath -RequireWaterField:([bool]$case.Field) | Out-Null }
    catch {$rejected=$true}
    if($rejected -ne $case.Reject) {throw "Smoke log checker regression: $($case.Name)"}
}
Write-Output 'Vulkan smoke log checker: 8 cases passed'
