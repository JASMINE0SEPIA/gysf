[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDir,
      [string]$ProviderJar='../voxy-vulkan-26.3/build/libs/voxy-vulkan-0.2.20-26.3-vulkan.11-dev-15ff198.jar',
      [string]$PythonPath='C:/Python314/python.exe')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskEnvironment=@{}
foreach($taskName in 'JAVA_TOOL_OPTIONS','VK_KHRONOS_VALIDATION_VALIDATE_SYNC','NGXSHIM_SYNC_TRACE') {
    $taskEnvironment[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')
}
Push-Location $taskRoot
try {
    if(!$env:JAVA_HOME -or !$env:VULKAN_SDK -or !$env:DLSS_SDK){throw 'Set JAVA_HOME, VULKAN_SDK and DLSS_SDK'}
    $taskGames=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object {$_.CommandLine -match '(KnotClient|net.minecraft.client.main.Main|--gameDir)'})
    if($taskGames.Count){throw 'Finish Minecraft before the isolated source capture'}
    $taskOutput=[IO.Path]::GetFullPath($OutputDir)
    if(Test-Path -LiteralPath $taskOutput){throw 'Use a fresh output directory'}
    New-Item -ItemType Directory -Path $taskOutput | Out-Null
    $taskProvider=(Resolve-Path -LiteralPath $ProviderJar).Path
    $taskGradle=@('--no-daemon','--console=plain','-PslangcPath=.tools/slang-2026.18/bin/slangc.exe','-PngxVendorConfig=rel')
    & .\gradlew.bat @taskGradle check compileSmokeTestJava *> (Join-Path $taskOutput 'build.log')
    if($LASTEXITCODE -ne 0){throw 'Source capture build failed; inspect build.log'}
    $taskHashes=[ordered]@{}
    foreach($taskPath in 'src/smokeTest/java/dev/comfyfluffy/caustica/rt/terrain/WaterSourceSmokeProbe.java',
        'src/smokeTest/java/dev/comfyfluffy/caustica/smoke/GysfSmokeClient.java',
        'src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtVoxyMesh.java','src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtWaterGeometry.java',
        'src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtWaterFieldImage.java') {
        $taskHashes[$taskPath]=(Get-FileHash -LiteralPath $taskPath).Hash
    }
    [ordered]@{SourceHead=(& git rev-parse HEAD);SourceDirty=[bool](& git status --porcelain);Sources=$taskHashes;
        ProviderSha256=(Get-FileHash -LiteralPath $taskProvider).Hash;
        JarSha256=(Get-FileHash -LiteralPath 'build/libs/gysf-0.7.0-dev+mc26.3.jar').Hash;
        Scope='Actual frozen world/provider mip inputs; CPU/Vulkan internal source-domain validation; no full optics or FPS acceptance'
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskOutput 'run.json') -Encoding utf8
    $taskInput=Join-Path $taskOutput 'game-input'
    $env:JAVA_TOOL_OPTIONS='-Dgysf.rt.finiteWater=true -Dcaustica.rt.omm=false -Dgysf.smoke.cleanWater=true '+
        '-Dgysf.smoke.waterCompareOnly=true -Dgysf.smoke.waterNearReference=true -Dgysf.smoke.renderDistance=12 '+
        '-Dgysf.smoke.waterSourceCapture=true -Dgysf.voxy.validateCoverage=true "-Dgysf.smoke.sourceDir='+$taskInput+'"'
    $env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true';$env:NGXSHIM_SYNC_TRACE='1'
    $taskLog=Join-Path $taskOutput 'game.log'
    & .\gradlew.bat @taskGradle runSmokeClient -PvooxxySmoke "-PvooxxySmokeJar=$taskProvider" -PvalidateVulkan *> $taskLog
    if($LASTEXITCODE -ne 0){throw 'Source capture game failed; inspect game.log'}
    if(!(Select-String -LiteralPath $taskLog -SimpleMatch 'GYSF_WATER_SOURCE: PASS' -Quiet)){throw 'Source capture PASS missing'}
    & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path $taskLog -RequireWaterBoundsCache -RequireNgxClearSync
    # Do not propagate the game-only Java options into the native probe's exporter process.
    $env:JAVA_TOOL_OPTIONS=$taskEnvironment['JAVA_TOOL_OPTIONS']
    & (Join-Path $PSScriptRoot 'Test-WaterLayers.ps1') -CapturedInputDir $taskInput -OutputDir (Join-Path $taskOutput 'probe') *> (Join-Path $taskOutput 'probe.log')
    & $PythonPath (Join-Path $PSScriptRoot 'Analyze-WaterSources.py') $taskInput --output (Join-Path $taskOutput 'edges.json') `
        --probe-input (Join-Path $taskOutput 'edge-input') *> (Join-Path $taskOutput 'edges.log')
    if($LASTEXITCODE -ne 0){throw 'Actual coincident-edge source policy failed; inspect edges.json'}
    & (Join-Path $PSScriptRoot 'Test-WaterLayers.ps1') -CapturedInputDir (Join-Path $taskOutput 'edge-input') `
        -OutputDir (Join-Path $taskOutput 'edge-probe') *> (Join-Path $taskOutput 'edge-probe.log')
    $taskDomain=Get-Content -LiteralPath (Join-Path $taskOutput 'probe/result.json') -Raw | ConvertFrom-Json
    $taskEdges=Get-Content -LiteralPath (Join-Path $taskOutput 'edge-probe/result.json') -Raw | ConvertFrom-Json
    if(!$taskDomain.SourceDomainQualified -or !$taskEdges.CapturedEdgesQualified){throw 'CPU-only or incomplete source qualification'}
    [ordered]@{State='source-domain-verified';CpuCases=8192;GpuCases=8192;CapturedEdgesQualified=$true;
        GameOpticsQualified=$false;SourceHead=(& git rev-parse HEAD);SourceDirty=[bool](& git status --porcelain);
        Scope='Actual provider sources and unchanged packets; finite solver in CPU/Vulkan compute; no full game optics, ownership or FPS acceptance'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOutput 'result.json') -Encoding utf8
    Write-Output "WATER_SOURCE_RESULT=$taskOutput"
} finally {
    foreach($taskName in $taskEnvironment.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskEnvironment[$taskName],'Process')}
    Pop-Location
}
