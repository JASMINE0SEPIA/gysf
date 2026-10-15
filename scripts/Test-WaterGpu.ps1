[CmdletBinding()]
param([string]$SlangcPath,[string]$OutputDir,[int]$RayCount=65536,[switch]$FinitePatch,[switch]$Trace,[switch]$Scene)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if(-not $env:VULKAN_SDK) { throw 'Set VULKAN_SDK to the installed Vulkan SDK' }
    if($Scene -and -not $Trace) { throw '-Scene requires -Trace' }
    if(-not $SlangcPath) { $SlangcPath=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe' }
    if(-not $OutputDir) { $OutputDir=Join-Path $taskRoot ('artifacts/'+(Get-Date -Format 'yyyy-MM-dd')+'/water-gpu/run-'+[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) }
    $taskBuild=Join-Path $taskRoot 'build/shader-test'
    New-Item -ItemType Directory -Force $taskBuild | Out-Null
    New-Item -ItemType Directory -Force $OutputDir | Out-Null
    $OutputDir=(Resolve-Path $OutputDir).Path
    if(Test-Path -LiteralPath (Join-Path $OutputDir 'results.json')) { throw 'Use a fresh output directory' }
    $taskShader=if($Trace){'src/shaderTest/water_patch_trace.slang'}elseif($FinitePatch){'src/shaderTest/water_patch_gpu.slang'}else{'src/shaderTest/water_gpu.slang'}
    foreach($taskCandidate in 0,1) {
        $taskSpv=Join-Path $OutputDir $(if($taskCandidate){'candidate.spv'}else{'production.spv'})
        $taskEntry=if($Trace){'patchIntersection'}else{'waterGpuProbe'}
        $taskStage=if($Trace){'intersection'}else{'compute'}
        & $SlangcPath $taskShader -I shaders/world -target spirv -profile spirv_1_5 -entry $taskEntry -stage $taskStage -O3 -D "GYSF_GPU_CANDIDATE=$taskCandidate" -o $taskSpv
        if($LASTEXITCODE -ne 0) { throw 'GPU equation shader compilation failed' }
        & (Join-Path $env:VULKAN_SDK 'Bin/spirv-val.exe') --target-env vulkan1.2 $taskSpv
        if($LASTEXITCODE -ne 0) { throw 'GPU equation SPIR-V validation failed' }
    }
    if($Trace) {
        foreach($taskStage in @(@('patchRaygen','raygeneration','raygen.spv'),@('patchMiss','miss','miss.spv'),@('patchClosest','closesthit','closest.spv'))) {
            $taskSpv=Join-Path $OutputDir $taskStage[2]
            & $SlangcPath $taskShader -I shaders/world -target spirv -profile spirv_1_5 -entry $taskStage[0] -stage $taskStage[1] -O3 -D GYSF_GPU_CANDIDATE=1 -o $taskSpv
            if($LASTEXITCODE -ne 0) { throw 'RT probe shader compilation failed' }
            & (Join-Path $env:VULKAN_SDK 'Bin/spirv-val.exe') --target-env vulkan1.2 $taskSpv
            if($LASTEXITCODE -ne 0) { throw 'RT probe SPIR-V validation failed' }
        }
    }
    $taskJdk=if($env:JAVA_HOME){$env:JAVA_HOME}else{'C:/Program Files/Java/jdk-25'}
    New-Item -ItemType Directory -Force (Join-Path $taskBuild 'classes') | Out-Null
    & (Join-Path $taskJdk 'bin/javac.exe') -d (Join-Path $taskBuild 'classes') src/main/java/dev/comfyfluffy/caustica/rt/RtWaterWaveDomain.java src/shaderTest/WaterGpuDomain.java
    if($LASTEXITCODE -ne 0) { throw 'CPU domain exporter compilation failed' }
    & (Join-Path $taskJdk 'bin/java.exe') -cp (Join-Path $taskBuild 'classes') dev.comfyfluffy.caustica.rt.WaterGpuDomain (Join-Path $OutputDir 'domain.txt')
    if($LASTEXITCODE -ne 0) { throw 'CPU domain export failed' }
    & cmake -S src/shaderTest -B $taskBuild -A x64
    if($LASTEXITCODE -ne 0) { throw 'GPU-test CMake configure failed' }
    $taskTarget=if($Trace){'water_trace'}else{'water_gpu'}
    & cmake --build $taskBuild --config Release --target $taskTarget
    if($LASTEXITCODE -ne 0) { throw 'GPU-test native build failed' }
    if($Trace) {
        if($Scene) {
            & (Join-Path $taskBuild 'Release/water_trace.exe') (Join-Path $OutputDir 'domain.txt') $OutputDir $RayCount scene
        } else {
            & (Join-Path $taskBuild 'Release/water_trace.exe') (Join-Path $OutputDir 'domain.txt') $OutputDir $RayCount
        }
    } else {
        $taskKind=if($FinitePatch){'finite-patch'}else{'grazing'}
        & (Join-Path $taskBuild 'Release/water_gpu.exe') (Join-Path $OutputDir 'production.spv') (Join-Path $OutputDir 'candidate.spv') (Join-Path $OutputDir 'domain.txt') $OutputDir $RayCount $taskKind
    }
    if($LASTEXITCODE -ne 0) { throw "GPU equation/validation gate failed; inspect $OutputDir/results.json and the log" }
    Write-Output "WATER_GPU_RESULTS=$OutputDir/results.json"
} finally { Pop-Location }
