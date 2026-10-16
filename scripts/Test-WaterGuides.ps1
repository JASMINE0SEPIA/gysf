[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OutputDir,
    [string]$ProviderJar='../voxy-vulkan-26.3/build/libs/voxy-vulkan-0.2.20-26.3-vulkan.10-dev-d4097ba.jar',
    [string]$PythonPath='C:/Python314/python.exe'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskEnvironment=@{}
foreach($taskName in 'JAVA_TOOL_OPTIONS','VK_KHRONOS_VALIDATION_VALIDATE_SYNC','NGXSHIM_SYNC_TRACE') {
    $taskEnvironment[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')
}
Push-Location $taskRoot
try {
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
    foreach($taskMode in 'mixed','near') {
        $taskCapture=Join-Path $taskOutput $taskMode
        $taskLog=Join-Path $taskOutput ($taskMode+'.log')
        $taskOptions='-Dgysf.rt.finiteWater=true -Dgysf.rt.captureGuides=true -Dcaustica.rt.omm=false '+
            '-Dgysf.smoke.cleanWater=true -Dgysf.smoke.waterCompareOnly=true -Dgysf.smoke.waterGuides=true '+
            '-Dgysf.smoke.requirePreciseFluids=true -Dgysf.voxy.validateCoverage=true '
        if($taskMode -eq 'near'){$taskOptions+='-Dgysf.smoke.waterNearReference=true '}
        $env:JAVA_TOOL_OPTIONS=$taskOptions+'"-Dgysf.smoke.guideDir='+$taskCapture+'"'
        & .\gradlew.bat @taskGradle runSmokeClient -PvooxxySmoke "-PvooxxySmokeJar=$taskProvider" -PvalidateVulkan *> $taskLog
        if($LASTEXITCODE -ne 0){throw "Guide game run failed: $taskLog"}
        & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path $taskLog -RequireNgxClearSync -RequireWaterField -RequireWaterGuides
    }
    $taskAnalysis=Join-Path $taskOutput 'analysis'
    & $PythonPath (Join-Path $PSScriptRoot 'Analyze-WaterGuides.py') (Join-Path $taskOutput 'mixed') (Join-Path $taskOutput 'near') --output $taskAnalysis --require-static --require-material *> (Join-Path $taskOutput 'analysis.log')
    if($LASTEXITCODE -ne 0){throw 'GPU guide numerical gate failed; inspect analysis.log and analysis/results.json'}
    [ordered]@{State='passed';Phases=4;Omm=$false;LiveRr=$true;Jitter=0;WaveSpeed=0;
        ProviderJar=$taskProvider;ProviderSha256=(Get-FileHash -LiteralPath $taskProvider).Hash;
        SourceHead=(& git rev-parse HEAD);Scope='Isolated game, raw GPU interfaces and guides; no FPS acceptance'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOutput 'pass.json') -Encoding utf8
    Write-Output "WATER_GUIDES_PASS=$taskOutput"
} finally {
    foreach($taskName in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskEnvironment[$taskName],'Process')}
    Pop-Location
}
