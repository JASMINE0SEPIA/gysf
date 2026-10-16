[CmdletBinding()]
param([switch]$CpuOnly,[string]$OutputDir)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskCompiler=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe'
    $taskBuild=Join-Path $taskRoot 'build/shader-test'
    foreach($taskImage in 'water-field-current.bin','water-field-previous.bin') {
        if(-not(Test-Path -LiteralPath (Join-Path $taskBuild $taskImage))) {
            throw 'Run Gradle test first to export the production Java field images.'
        }
    }
    if(-not $OutputDir){$OutputDir=Join-Path $taskRoot ('artifacts/'+(Get-Date -Format 'yyyy-MM-dd')+'/water-field/run-'+[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())}
    if((Test-Path -LiteralPath (Join-Path $OutputDir 'pass.json')) -or (Test-Path -LiteralPath (Join-Path $OutputDir 'readback.csv'))) {
        throw 'Use a fresh field-probe output directory'
    }
    New-Item -ItemType Directory -Force $OutputDir | Out-Null
    & $taskCompiler src/shaderTest/water_field_probe.slang -I shaders/world -target cpp -o (Join-Path $taskBuild 'water-field-generated.cpp')
    if($LASTEXITCODE -ne 0){throw 'Field host shader compilation failed'}
    if(-not $CpuOnly) {
        $taskGames=@(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object {$_.CommandLine -match '(KnotClient|net.minecraft.client.main.Main|--gameDir)'})
        if($taskGames.Count){throw 'Minecraft is running; GPU field probe must run alone'}
        if(-not $env:VULKAN_SDK){throw 'Set VULKAN_SDK before running the GPU field probe'}
        & $taskCompiler src/shaderTest/water_field_probe.slang -I shaders/world -target spirv -profile spirv_1_5 -entry fieldProbe -stage compute -O3 -o (Join-Path $OutputDir 'field.spv')
        if($LASTEXITCODE -ne 0){throw 'Field SPIR-V compilation failed'}
        & (Join-Path $env:VULKAN_SDK 'Bin/spirv-val.exe') --target-env vulkan1.2 (Join-Path $OutputDir 'field.spv')
        if($LASTEXITCODE -ne 0){throw 'Field SPIR-V validation failed'}
    }
    & cmake -S src/shaderTest -B $taskBuild -A x64
    if($LASTEXITCODE -ne 0){throw 'Field CMake configure failed'}
    $taskTargets=@('water_field_host');if(-not $CpuOnly){$taskTargets+='water_field_gpu'}
    & cmake --build $taskBuild --config Release --target @taskTargets
    if($LASTEXITCODE -ne 0){throw 'Field native build failed'}
    & (Join-Path $taskBuild 'Release/water_field_host.exe') (Join-Path $taskBuild 'water-field-current.bin') (Join-Path $taskBuild 'water-field-previous.bin') | Tee-Object -FilePath (Join-Path $OutputDir 'host.log')
    if($LASTEXITCODE -ne 0){throw 'Field host correctness gate failed'}
    if(-not $CpuOnly) {
        $taskJdk=if($env:JAVA_HOME){$env:JAVA_HOME}else{'C:/Program Files/Java/jdk-25'}
        New-Item -ItemType Directory -Force (Join-Path $taskBuild 'classes') | Out-Null
        & (Join-Path $taskJdk 'bin/javac.exe') -d (Join-Path $taskBuild 'classes') src/main/java/dev/comfyfluffy/caustica/rt/RtWaterWaveDomain.java src/shaderTest/WaterGpuDomain.java
        if($LASTEXITCODE -ne 0){throw 'Domain exporter compile failed'}
        & (Join-Path $taskJdk 'bin/java.exe') -cp (Join-Path $taskBuild 'classes') dev.comfyfluffy.caustica.rt.WaterGpuDomain (Join-Path $OutputDir 'domain.txt')
        if($LASTEXITCODE -ne 0){throw 'Domain export failed'}
        & (Join-Path $taskBuild 'Release/water_field_gpu.exe') (Join-Path $OutputDir 'field.spv') (Join-Path $OutputDir 'domain.txt') $taskBuild (Join-Path $OutputDir 'readback.csv') | Tee-Object -FilePath (Join-Path $OutputDir 'gpu.log')
        if($LASTEXITCODE -ne 0){throw 'Field Vulkan correctness gate failed'}
    }
    Copy-Item -LiteralPath (Join-Path $taskBuild 'water-field-current.bin'),(Join-Path $taskBuild 'water-field-previous.bin') -Destination $OutputDir
    [ordered]@{State='passed';CpuCases=4096;GpuExecuted=(-not [bool]$CpuOnly);
        CurrentSha256=(Get-FileHash -LiteralPath (Join-Path $OutputDir 'water-field-current.bin')).Hash;
        PreviousSha256=(Get-FileHash -LiteralPath (Join-Path $OutputDir 'water-field-previous.bin')).Hash
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDir 'pass.json') -Encoding utf8
    Write-Output "WATER_FIELD_PASS=$OutputDir"
} finally {Pop-Location}
