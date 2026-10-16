[CmdletBinding()]
param([switch]$Pressure,[string]$Label=('water-cache-'+(Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$ProviderJar='../voxy-vulkan-26.3/build/libs/voxy-vulkan-0.2.20-26.3-vulkan.13-dev-f0ed9dd.jar')
$ErrorActionPreference='Stop'
if($Label -notmatch '^[A-Za-z0-9_-]+$'){throw 'Use a fresh simple label'}
if(!$env:VULKAN_SDK -or !$env:DLSS_SDK){throw 'Set VULKAN_SDK and DLSS_SDK'}
$taskGames=@(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object {$_.CommandLine -match '(KnotClient|net.minecraft.client.main.Main|--gameDir)'})
if($taskGames.Count){throw 'Minecraft is already running'}
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskSavedJava=$env:JAVA_TOOL_OPTIONS;$taskSavedSync=$env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC;$taskSavedNgx=$env:NGXSHIM_SYNC_TRACE
Push-Location $taskRoot
try {
    if(Test-Path -LiteralPath "$Label.log"){throw 'Use a fresh label'}
    $taskLimit=if($Pressure){1}else{64}
    $env:JAVA_TOOL_OPTIONS="-Dgysf.smoke.performance=true -Dgysf.smoke.waterCacheLifecycle=true -Dgysf.smoke.waterCachePressure=$($Pressure.ToString().ToLowerInvariant()) -Dgysf.smoke.renderDistance=4 -Dgysf.smoke.width=854 -Dgysf.smoke.height=480 -Dgysf.rt.finiteWater=true -Dcaustica.rt.omm=false -Dgysf.voxy.validateCoverage=true -Dgysf.rt.waterBoundsCacheMiB=$taskLimit"
    $env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC='true';$env:NGXSHIM_SYNC_TRACE='1'
    & .\gradlew.bat --no-daemon '-Dhttps.proxyHost=' '-Dhttp.proxyHost=' '-PslangcPath=.tools/slang-2026.18/bin/slangc.exe' -PngxVendorConfig=rel -PsmokeMaxBounces=4 -PvooxxySmoke "-PvooxxySmokeJar=$ProviderJar" -PvalidateVulkan runSmokeClient --console=plain *> "$Label.log"
    if($LASTEXITCODE -ne 0){throw "Cache lifecycle failed: $Label.log"}
    if(!(Select-String -LiteralPath "$Label.log" -SimpleMatch 'GYSF_CACHE_LIFECYCLE: PASS' -Quiet)){throw 'Lifecycle PASS is missing'}
    & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path "$Label.log" -RequireNgxClearSync -RequireWaterBoundsCache
    Write-Output "Water cache lifecycle passed: $Label.log"
} finally {
    $env:JAVA_TOOL_OPTIONS=$taskSavedJava;$env:VK_KHRONOS_VALIDATION_VALIDATE_SYNC=$taskSavedSync;$env:NGXSHIM_SYNC_TRACE=$taskSavedNgx
    Pop-Location
}
