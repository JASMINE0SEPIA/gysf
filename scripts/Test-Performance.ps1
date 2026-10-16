[CmdletBinding()]
param(
    [ValidateRange(4,32)][int]$RenderDistance=32,
    [ValidateRange(640,3840)][int]$Width=1792,
    [ValidateRange(360,2160)][int]$Height=1120,
    [ValidateRange(30,600)][int]$InitialSeconds=180,
    [ValidateRange(5,120)][int]$SettleSeconds=15,
    [ValidateRange(10,120)][int]$MeasureSeconds=20,
    [ValidatePattern('^(?:0|4|6|-1)(?:,(?:0|4|6|-1))*$')][string]$Presets='0,4,6,-1,6,4,0',
    [ValidatePattern('^[0-5](?:,[0-5])*$')][string]$Qualities='0',
    [switch]$NearMemoryPressure,[switch]$PressureOnly,[switch]$ValidateVulkan,[switch]$EnableOmm,[switch]$MemoryDiagnostic,
    [switch]$FiniteWater,[switch]$GpuTimings,[switch]$CpuStats,
    [switch]$DisableWaterBoundsCache,
    [ValidateRange(0,256)][int]$WaterBoundsCacheMiB=64,
    [switch]$CompareWaterBounds,[switch]$CompareWaterCache,
    [ValidatePattern('^[01](?:,[01])*$')][string]$WaterBoundsSequence='0,1,1,0',
    [string]$WaterShaderBaseline='',
    [string]$WaterHitShaderBaseline='',
    [ValidatePattern('^[01](?:,[01])*$')][string]$WaterShaderSequence='0',
    [string]$Label=('perf-'+(Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$ProviderJar='../voxy-vulkan-26.3/build/libs/voxy-vulkan-0.2.20-26.3-vulkan.10-dev-d4097ba.jar'
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
    if($Qualities.Split(',').Count -ne 1 -and $Qualities.Split(',').Count -ne $Presets.Split(',').Count){throw 'Quality count must be one or match presets'}
    $taskFlags=@("-Dcaustica.rt.omm=$($EnableOmm.ToString().ToLowerInvariant())","-Dgysf.voxy.validateCoverage=$($ValidateVulkan.ToString().ToLowerInvariant())",'-Dgysf.smoke.performance=true',
        "-Dgysf.smoke.renderDistance=$RenderDistance","-Dgysf.smoke.width=$Width","-Dgysf.smoke.height=$Height",
        "-Dgysf.smoke.perfInitialSeconds=$InitialSeconds","-Dgysf.smoke.perfSettleSeconds=$SettleSeconds",
        "-Dgysf.smoke.perfMeasureSeconds=$MeasureSeconds","-Dgysf.smoke.perfLabel=$Label","-Dgysf.smoke.perfPresets=$Presets",
        "-Dgysf.smoke.perfQualities=$Qualities","-Dgysf.rt.finiteWater=$($FiniteWater.ToString().ToLowerInvariant())")
    if($GpuTimings){$taskFlags+='"-Dgysf.rt.gpuTimings='+[IO.Path]::GetFullPath("run-smoke/performance-smoke/$Label/gpu.csv")+'"'}
    if($CpuStats){$taskFlags+='-Dgysf.smoke.perfCpuStats=true'}
    if($DisableWaterBoundsCache){$taskFlags+='-Dgysf.rt.waterBoundsCache=false'}
    $taskFlags+="-Dgysf.rt.waterBoundsCacheMiB=$WaterBoundsCacheMiB"
    if($CompareWaterBounds) {
        if($CompareWaterCache){throw 'Choose shadow bounds OR runtime cache comparison'}
        if(!$FiniteWater -or $WaterShaderBaseline){throw 'Bounds comparison requires finite water and cannot combine with shader comparison'}
        if($WaterBoundsSequence.Split(',').Count -ne 1 -and $WaterBoundsSequence.Split(',').Count -ne $Presets.Split(',').Count){throw 'Bounds sequence count must be one or match presets'}
        $taskFlags+='-Dgysf.smoke.compareWaterBounds=true'
        $taskFlags+='-Dgysf.rt.waterBoundsCache=false'
        $taskFlags+="-Dgysf.smoke.waterBoundsSequence=$WaterBoundsSequence"
    }
    if($CompareWaterCache) {
        if(!$FiniteWater -or $DisableWaterBoundsCache -or $WaterBoundsCacheMiB -eq 0 -or $WaterShaderBaseline){throw 'Runtime cache comparison requires finite water, an enabled cache and no shader comparison'}
        if($WaterBoundsSequence.Split(',').Count -ne 1 -and $WaterBoundsSequence.Split(',').Count -ne $Presets.Split(',').Count){throw 'Bounds sequence count must be one or match presets'}
        $taskFlags+='-Dgysf.smoke.compareWaterCache=true'
        $taskFlags+="-Dgysf.smoke.waterBoundsSequence=$WaterBoundsSequence"
    }
    if($WaterHitShaderBaseline -and !$WaterShaderBaseline){throw 'Hit-stage comparison requires -WaterShaderBaseline'}
    if($WaterShaderBaseline) {
        if(!$FiniteWater){throw 'Shader comparison requires -FiniteWater'}
        if($WaterShaderSequence.Split(',').Count -ne 1 -and $WaterShaderSequence.Split(',').Count -ne $Presets.Split(',').Count){throw 'Shader sequence count must be one or match presets'}
        $taskBaseline=(Resolve-Path -LiteralPath $WaterShaderBaseline).Path
        $taskFlags+='"-Dgysf.smoke.waterShaderBaseline='+$taskBaseline+'"'
        $taskFlags+="-Dgysf.smoke.waterShaderSequence=$WaterShaderSequence"
        if($WaterHitShaderBaseline) {
            $taskHitBaseline=(Resolve-Path -LiteralPath $WaterHitShaderBaseline).Path
            $taskFlags+='"-Dgysf.smoke.waterHitShaderBaseline='+$taskHitBaseline+'"'
        }
    }
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
    [ordered]@{validation=[bool]$ValidateVulkan;gpuTimings=[bool]$GpuTimings;cpuStats=[bool]$CpuStats;
        finiteWater=[bool]$FiniteWater;omm=[bool]$EnableOmm;presets=$Presets;qualities=$Qualities;
        compareWaterBounds=[bool]$CompareWaterBounds;compareWaterCache=[bool]$CompareWaterCache;waterBoundsSequence=$WaterBoundsSequence;
        waterBoundsCache=([bool]$FiniteWater -and !$CompareWaterBounds -and !$DisableWaterBoundsCache -and $WaterBoundsCacheMiB -gt 0);
        waterBoundsCacheMiB=$WaterBoundsCacheMiB;
        sourceHead=(& git rev-parse HEAD);sourceDirty=[bool](& git status --porcelain);
        providerSha256=(Get-FileHash -LiteralPath $ProviderJar).Hash;initialSeconds=$InitialSeconds;
        waterShaderBaseline=if($WaterShaderBaseline){$taskBaseline}else{''};waterShaderSequence=$WaterShaderSequence;
        waterShaderBaselineSha256=if($WaterShaderBaseline){(Get-FileHash -LiteralPath $taskBaseline).Hash.ToLowerInvariant()}else{''};
        waterHitShaderBaseline=if($WaterHitShaderBaseline){$taskHitBaseline}else{''};
        waterHitShaderBaselineSha256=if($WaterHitShaderBaseline){(Get-FileHash -LiteralPath $taskHitBaseline).Hash.ToLowerInvariant()}else{''};
        settleSeconds=$SettleSeconds;measureSeconds=$MeasureSeconds
    } | ConvertTo-Json | Set-Content -LiteralPath "run-smoke/performance-smoke/$Label/run.json" -Encoding utf8
    if($NearMemoryPressure) {
        & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path "$Label.log" -RequireNearPressure `
            -RequireWaterBoundsCache:($FiniteWater -and !$CompareWaterBounds -and !$DisableWaterBoundsCache -and $WaterBoundsCacheMiB -gt 0)
    }
    if($WaterShaderBaseline -and !(Select-String -LiteralPath "$Label.log" -Pattern 'GYSF_SHADER_COMPARE: complete switches=\d+ liveAlternates=0' -Quiet)) {
        throw 'Missing alternate-pipeline lifetime qualification'
    }
    if($CompareWaterBounds) {
        $taskExpectedSelections=$Presets.Split(',').Count
        if(!(Select-String -LiteralPath "$Label.log" -Pattern "GYSF_BOUNDS_COMPARE: complete selections=$taskExpectedSelections released=[1-9]\d* liveAlternates=0" -Quiet)) {
            throw 'Missing alternate-water-BLAS lifetime qualification'
        }
    }
    if($CompareWaterCache) {
        $taskExpectedSelections=$Presets.Split(',').Count
        if(!(Select-String -LiteralPath "$Label.log" -Pattern "GYSF_BOUNDS_COMPARE: complete selections=$taskExpectedSelections released=0 liveAlternates=0" -Quiet)) {
            throw 'Runtime cache comparison unexpectedly allocated shadow BLAS or missed selections'
        }
        & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path "$Label.log" -RequireWaterBoundsCache
    }
    Write-Output "Evidence: run-smoke/performance-smoke/$Label; log: $Label.log"
} finally { $env:JAVA_TOOL_OPTIONS=$savedJavaOptions;Pop-Location }
