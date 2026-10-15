[CmdletBinding()]
param([switch]$Smoke, [switch]$Joint, [switch]$Native, [switch]$ValidateVulkan, [string]$SlangcPath)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if ($Native) {
        if (-not $env:DLSS_SDK -or -not $env:VULKAN_SDK) { throw 'Set DLSS_SDK and VULKAN_SDK before building the NGX shim.' }
        & cmake -S native/ngx_shim -B build/native/ngx_shim-cmake -A x64
        if ($LASTEXITCODE -ne 0) { throw 'NGX configure failed.' }
        & cmake --build build/native/ngx_shim-cmake --config Release
        if ($LASTEXITCODE -ne 0) { throw 'NGX build failed.' }
    }
    $taskArgs=@('--no-daemon','check','compileSmokeTestJava','--console=plain')
    $localSlang=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe'
    if ($SlangcPath) { $localSlang=(Resolve-Path -LiteralPath $SlangcPath).Path }
    if (Test-Path -LiteralPath $localSlang) { $taskArgs+="-PslangcPath=$localSlang" }
    if ($env:DLSS_SDK) { $taskArgs+='-PngxVendorConfig=rel' }
    & .\gradlew.bat @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'gysf verification failed.' }
    if ($Smoke -or $Joint) {
        $taskRun=@('--no-daemon','runSmokeClient','--console=plain')
        if (Test-Path -LiteralPath $localSlang) { $taskRun+="-PslangcPath=$localSlang" }
        if ($env:DLSS_SDK) { $taskRun+='-PngxVendorConfig=rel' }
        if ($Joint) { $taskRun+='-PvooxxySmoke' }
        if ($ValidateVulkan) { $taskRun+='-PvalidateVulkan' }
        $smokeLog=Join-Path $taskRoot 'build/validation-smoke.log'
        & .\gradlew.bat @taskRun 2>&1 | Tee-Object -FilePath $smokeLog
        if ($LASTEXITCODE -ne 0) { throw 'gysf Vulkan smoke run failed.' }
        & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path $smokeLog
    }
} finally { Pop-Location }
