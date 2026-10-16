[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OutputDir,
    [string]$ProviderJar='../voxy-vulkan-26.3/build/libs/voxy-vulkan-0.2.20-26.3-vulkan.12-dev-cf3e16a.jar',
    [string]$PythonPath='C:/Python314/python.exe',
    [switch]$Optics,[switch]$Varied
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskEnvironment=@{}
foreach($taskName in 'JAVA_TOOL_OPTIONS','VK_KHRONOS_VALIDATION_VALIDATE_SYNC','NGXSHIM_SYNC_TRACE') {
    $taskEnvironment[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')
}
Push-Location $taskRoot
try {
    if($Optics -and $Varied){throw 'Use a separate varied mixed/near comparison'}
    if(-not $env:JAVA_HOME -or -not $env:VULKAN_SDK -or -not $env:DLSS_SDK) {
        throw 'Set JAVA_HOME, VULKAN_SDK and DLSS_SDK before the isolated GPU guide test'
    }
    $taskGames=@(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
        Where-Object {$_.CommandLine -match '(KnotClient|net.minecraft.client.main.Main|--gameDir)'})
    if($taskGames.Count){throw 'Minecraft is running; the guide test requires exclusive GPU use'}
    $taskOutput=[IO.Path]::GetFullPath($OutputDir)
    if(Test-Path -LiteralPath $taskOutput){throw 'Use a fresh output directory; existing evidence is never overwritten'}
    $taskProvider=(Resolve-Path -LiteralPath $ProviderJar).Path
    New-Item -ItemType Directory -Path $taskOutput | Out-Null
    $taskGradle=@('--no-daemon','--console=plain','-PslangcPath=.tools/slang-2026.18/bin/slangc.exe','-PngxVendorConfig=rel')
    & .\gradlew.bat @taskGradle check compileSmokeTestJava *> (Join-Path $taskOutput 'build.log')
    if($LASTEXITCODE -ne 0){throw 'Guide test build/check failed; inspect build.log'}
    $env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true';$env:NGXSHIM_SYNC_TRACE='1'
    $taskModes=if($Optics){@('mixed')}else{@('mixed','near')}
    $taskPhaseCount=if($Optics){8}else{4}
    foreach($taskMode in $taskModes) {
        $taskCapture=Join-Path $taskOutput $taskMode
        $taskLog=Join-Path $taskOutput ($taskMode+'.log')
        $taskOptions='-Dgysf.rt.finiteWater=true -Dgysf.rt.captureGuides=true -Dcaustica.rt.omm=false '+
            '-Dgysf.smoke.cleanWater=true -Dgysf.smoke.waterCompareOnly=true -Dgysf.smoke.waterGuides=true '+
            '-Dgysf.smoke.requirePreciseFluids=true -Dgysf.voxy.validateCoverage=true '
        if($taskMode -eq 'near'){$taskOptions+='-Dgysf.smoke.waterNearReference=true '}
        if($Varied){$taskOptions+='-Dgysf.smoke.waterVaried=true -Dcaustica.rt.particles=false '}
        if($Optics){$taskOptions+='-Dgysf.rt.captureResolvedGuides=true -Dgysf.smoke.waterOptics=true -Dgysf.smoke.width=427 -Dgysf.smoke.height=240 '}
        $env:JAVA_TOOL_OPTIONS=$taskOptions+'"-Dgysf.smoke.guideDir='+$taskCapture+'"'
        & .\gradlew.bat @taskGradle runSmokeClient -PvooxxySmoke "-PvooxxySmokeJar=$taskProvider" -PvalidateVulkan *> $taskLog
        if($LASTEXITCODE -ne 0){throw "Guide game run failed: $taskLog"}
        & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path $taskLog -RequireNgxClearSync -RequireWaterField -RequireWaterGuides -GuideCaptureCount $taskPhaseCount
    }
    $taskAnalysis=Join-Path $taskOutput 'analysis'
    if($Optics) {
        & $PythonPath (Join-Path $PSScriptRoot 'Analyze-WaterOptics.py') (Join-Path $taskOutput 'mixed') --output $taskAnalysis *> (Join-Path $taskOutput 'analysis.log')
    } else {
        & $PythonPath (Join-Path $PSScriptRoot 'Analyze-WaterGuides.py') (Join-Path $taskOutput 'mixed') (Join-Path $taskOutput 'near') --output $taskAnalysis --require-static --require-material *> (Join-Path $taskOutput 'analysis.log')
    }
    if($LASTEXITCODE -ne 0){throw 'GPU guide numerical gate failed; inspect analysis.log and analysis/results.json'}
    [ordered]@{State='passed';Phases=$taskPhaseCount;Optics=[bool]$Optics;Varied=[bool]$Varied;Omm=$false;LiveRr=$true;Jitter=0;WaveSpeed=0;
        ProviderJar=$taskProvider;ProviderSha256=(Get-FileHash -LiteralPath $taskProvider).Hash;
        SourceHead=(& git rev-parse HEAD);Scope='Isolated game, raw GPU interfaces and guides; no FPS acceptance'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOutput 'pass.json') -Encoding utf8
    Write-Output "WATER_GUIDES_PASS=$taskOutput"
} finally {
    foreach($taskName in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskEnvironment[$taskName],'Process')}
    Pop-Location
}
