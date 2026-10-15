[CmdletBinding()]
param(
    [ValidateRange(4,32)][int]$RenderDistance=32,
    [ValidateRange(640,3840)][int]$Width=1792,
    [ValidateRange(360,2160)][int]$Height=1120,
    [ValidateRange(30,600)][int]$InitialSeconds=180,
    [ValidateRange(5,120)][int]$SettleSeconds=15,
    [ValidateRange(10,120)][int]$MeasureSeconds=20,
    [ValidatePattern('^(?:0|4|6|-1)(?:,(?:0|4|6|-1))*$')][string]$Presets='0,4,6,-1,6,4,0',
    [switch]$NearMemoryPressure,[switch]$PressureOnly,[switch]$ValidateVulkan,[switch]$EnableOmm,[switch]$MemoryDiagnostic,
    [string]$Label=('perf-'+(Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$ProviderJar='../voxy-vulkan-26.3/build/libs/voxy-vulkan-0.2.20-26.3-vulkan.8-dev-0e4365e.jar'
)
$ErrorActionPreference='Stop'
if($Label -notmatch '^[A-Za-z0-9_-]+$'){throw 'Use a simple unique output label'}
if($PressureOnly -and !$NearMemoryPressure){throw '-PressureOnly needs -NearMemoryPressure'}
if(!$env:VULKAN_SDK -or !$env:DLSS_SDK){throw 'Set VULKAN_SDK and DLSS_SDK (310.9.1 or later)'}
$taskGames=@(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
    Where-Object {$_.CommandLine -match '(KnotClient|net.minecraft.client.main.Main|--gameDir)'})
if($taskGames.Count){throw 'Minecraft is running; finish that session before measuring the GPU'}
$taskRoot=Split-Path -Parent $PSScriptRoot
$savedJavaOptions=$env:JAVA_TOOL_OPTIONS
Push-Location $taskRoot
try {
    if(Test-Path -LiteralPath "run-smoke/performance-smoke/$Label"){throw 'Use a fresh output label'}
    $taskFlags=@("-Dcaustica.rt.omm=$($EnableOmm.ToString().ToLowerInvariant())",'-Dgysf.voxy.validateCoverage=true','-Dgysf.smoke.performance=true',
        "-Dgysf.smoke.renderDistance=$RenderDistance","-Dgysf.smoke.width=$Width","-Dgysf.smoke.height=$Height",
        "-Dgysf.smoke.perfInitialSeconds=$InitialSeconds","-Dgysf.smoke.perfSettleSeconds=$SettleSeconds",
        "-Dgysf.smoke.perfMeasureSeconds=$MeasureSeconds","-Dgysf.smoke.perfLabel=$Label","-Dgysf.smoke.perfPresets=$Presets")
    if($NearMemoryPressure){$taskFlags+='-Dgysf.smoke.nearMemoryPressure=true'}
    if($PressureOnly){$taskFlags+='-Dgysf.smoke.nearMemoryPressureOnly=true'}
    if($EnableOmm){$taskFlags+='-Dcaustica.rt.ommStats=true'}
    if($MemoryDiagnostic){$taskFlags+='-Dgysf.smoke.memoryDiagnosticOnly=true'}
    $env:JAVA_TOOL_OPTIONS=$taskFlags -join ' '
    $taskArgs=@('--no-daemon','-Dhttps.proxyHost=','-Dhttp.proxyHost=',
        '-PslangcPath=.tools/slang-2026.18/bin/slangc.exe','-PngxVendorConfig=rel','-PsmokeMaxBounces=4',
        '-PvooxxySmoke',"-PvooxxySmokeJar=$ProviderJar",'runSmokeClient','--console=plain')
    if($ValidateVulkan){$taskArgs+='-PvalidateVulkan'}
    & .\gradlew.bat @taskArgs *> "$Label.log"
    if($LASTEXITCODE -ne 0){throw "Performance run failed; inspect $Label.log"}
    $taskCompletion=if($MemoryDiagnostic){'GYSF_MEMORY_DIAG: COMPLETE'}elseif($PressureOnly){'GYSF_NEAR_PRESSURE: PASS'}else{'GYSF_PERF: PASS'}
    if(!(Select-String -LiteralPath "$Label.log" -SimpleMatch $taskCompletion -Quiet)){
        throw "The client exited without completing the requested check; inspect $Label.log"
    }
    Write-Output "Evidence: run-smoke/performance-smoke/$Label; log: $Label.log"
} finally { $env:JAVA_TOOL_OPTIONS=$savedJavaOptions;Pop-Location }
