[CmdletBinding()]
param([switch]$Diagnose,[switch]$CpuOnly,[Parameter(Mandatory)][string]$OutputDir,[string]$CapturedInputDir='')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskGames=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {$_.CommandLine -match '(KnotClient|net.minecraft.client.main.Main|--gameDir)'})
    if($taskGames.Count){throw 'Finish Minecraft before compiling or executing this probe'}
    if(!$env:JAVA_HOME -or (!$CpuOnly -and !$env:VULKAN_SDK)){throw 'Set JAVA_HOME and VULKAN_SDK'}
    $taskCapture=$null
    if($CapturedInputDir) {
        if($Diagnose){throw 'Historical synthetic diagnosis cannot use captured source data'}
        $taskCapture=(Resolve-Path -LiteralPath $CapturedInputDir).Path
        $taskCaptureInfo=Get-Content -LiteralPath (Join-Path $taskCapture 'capture.json') -Raw | ConvertFrom-Json
        if($taskCaptureInfo.kind -notin @('actual-provider-mips','actual-provider-edge-pairs') -or $taskCaptureInfo.cases -ne 4096 -or $taskCaptureInfo.anchorY -ne 128 -or
           $taskCaptureInfo.levels.Count -ne 5 -or ($taskCaptureInfo.levels | Where-Object {$_ -le 0}) -or
           $taskCaptureInfo.varyingCases -le 0 -or $taskCaptureInfo.zeroCases -le 0 -or $taskCaptureInfo.wetCases -le 0 -or $taskCaptureInfo.partialCases -le 0 -or
           $taskCaptureInfo.knownNodes -le 0 -or $taskCaptureInfo.unknownNodes -le 0 -or $taskCaptureInfo.knownZeroNodes -le 0 -or $taskCaptureInfo.unknownAnchorXZ -ne 65536) {
            throw 'Captured source controls or mip coverage are incomplete'
        }
    }
    $taskOutput=[IO.Path]::GetFullPath($OutputDir)
    if(Test-Path -LiteralPath $taskOutput){throw 'Use a fresh output directory'}
    New-Item -ItemType Directory -Path $taskOutput | Out-Null
    $taskBuild=Join-Path $taskRoot 'build/shader-test'
    foreach($taskImage in 'water-field-current.bin','water-field-previous.bin','water-field-full-cell.bin') {
        $taskImagePath=Join-Path $(if($taskCapture){$taskCapture}else{$taskBuild}) $taskImage
        if(!(Test-Path -LiteralPath $taskImagePath)){throw 'Run Gradle test to export canonical field images first'}
        Copy-Item -LiteralPath $taskImagePath -Destination $taskOutput
    }
    $taskClasses=Join-Path $taskBuild 'layer-classes';New-Item -ItemType Directory -Force $taskClasses | Out-Null
    & "$env:JAVA_HOME/bin/javac.exe" -d $taskClasses src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtWaterGeometry.java src/shaderTest/WaterLayerInputs.java src/main/java/dev/comfyfluffy/caustica/rt/RtWaterWaveDomain.java src/shaderTest/WaterGpuDomain.java
    if($LASTEXITCODE -ne 0){throw 'Production fixture exporter compilation failed'}
    if($taskCapture) {
        Copy-Item -LiteralPath (Join-Path $taskCapture 'water-layer-primitives.bin') -Destination $taskOutput
        Copy-Item -LiteralPath (Join-Path $taskCapture 'capture.json') -Destination $taskOutput
    } else {
        & "$env:JAVA_HOME/bin/java.exe" -cp $taskClasses dev.comfyfluffy.caustica.rt.terrain.WaterLayerInputs (Join-Path $taskOutput 'water-layer-primitives.bin')
        if($LASTEXITCODE -ne 0){throw 'Production primitive export failed'}
    }
    & "$env:JAVA_HOME/bin/java.exe" -cp $taskClasses dev.comfyfluffy.caustica.rt.WaterGpuDomain (Join-Path $taskOutput 'domain.txt')
    if($LASTEXITCODE -ne 0){throw 'Domain export failed'}
    $taskHashes=[ordered]@{}
    foreach($taskSource in 'src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtWaterGeometry.java','src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtWaterFieldImage.java','shaders/world/water_primitive.slang','shaders/world/water_field.slang','shaders/world/water_patch.slang','shaders/world/water.slang','src/shaderTest/water_layer_probe.slang','src/shaderTest/water_captured_probe.slang','src/smokeTest/java/dev/comfyfluffy/caustica/rt/terrain/WaterSourceSmokeProbe.java','src/shaderTest/WaterLayerInputs.java','src/shaderTest/water_layer_host.cpp','src/shaderTest/water_layer_gpu.cpp') {
        $taskHashes[$taskSource]=(Get-FileHash -LiteralPath $taskSource).Hash
    }
    [ordered]@{Diagnose=[bool]$Diagnose;CpuOnly=[bool]$CpuOnly;SourceHead=(& git rev-parse HEAD);SourceDirty=[bool](& git status --porcelain);
        Sources=$taskHashes;PrimitiveSha256=(Get-FileHash -LiteralPath (Join-Path $taskOutput 'water-layer-primitives.bin')).Hash;
        CurrentFieldSha256=(Get-FileHash -LiteralPath (Join-Path $taskOutput 'water-field-current.bin')).Hash;
        PreviousFieldSha256=(Get-FileHash -LiteralPath (Join-Path $taskOutput 'water-field-previous.bin')).Hash
        FullCellFieldSha256=(Get-FileHash -LiteralPath (Join-Path $taskOutput 'water-field-full-cell.bin')).Hash
        CaptureMetadataSha256=if($taskCapture){(Get-FileHash -LiteralPath (Join-Path $taskCapture 'capture.json')).Hash}else{$null}
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskOutput 'run.json') -Encoding utf8
    $taskExpected=if($Diagnose){0}else{1}
    $taskSlang=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe'
    $taskShader=if($taskCapture){'src/shaderTest/water_captured_probe.slang'}else{'src/shaderTest/water_layer_probe.slang'}
    & $taskSlang $taskShader -I shaders/world -I src/shaderTest "-DEXPECT_CONTINUOUS=$taskExpected" -target cpp -o (Join-Path $taskBuild 'water-layer-generated.cpp')
    if($LASTEXITCODE -ne 0){throw 'Layer host shader compilation failed'}
    & cmake -S src/shaderTest -B $taskBuild -A x64
    if($LASTEXITCODE -ne 0){throw 'Layer CMake configure failed'}
    $taskTargets=@('water_layer_host');if(!$CpuOnly){$taskTargets+='water_layer_gpu'}
    & cmake --build $taskBuild --config Release --target @taskTargets
    if($LASTEXITCODE -ne 0){throw 'Layer native build failed'}
    & (Join-Path $taskBuild 'Release/water_layer_host.exe') (Join-Path $taskOutput 'water-field-current.bin') (Join-Path $taskOutput 'water-field-previous.bin') (Join-Path $taskOutput 'water-layer-primitives.bin') (Join-Path $taskOutput 'water-field-full-cell.bin') (Join-Path $taskOutput 'host.csv') | Tee-Object -FilePath (Join-Path $taskOutput 'host.log')
    if($LASTEXITCODE -ne 0){throw 'Layer CPU gate failed; this is not continuous-water acceptance'}
    $taskMaxGapDelta=$null
    if(!$CpuOnly) {
        & $taskSlang $taskShader -I shaders/world -I src/shaderTest "-DEXPECT_CONTINUOUS=$taskExpected" -target spirv -profile spirv_1_5 -entry fieldProbe -stage compute -O3 -o (Join-Path $taskOutput 'layer.spv')
        if($LASTEXITCODE -ne 0){throw 'Layer SPIR-V compilation failed'}
        & "$env:VULKAN_SDK/Bin/spirv-val.exe" --target-env vulkan1.2 (Join-Path $taskOutput 'layer.spv')
        if($LASTEXITCODE -ne 0){throw 'Layer SPIR-V validation failed'}
        & (Join-Path $taskBuild 'Release/water_layer_gpu.exe') (Join-Path $taskOutput 'layer.spv') (Join-Path $taskOutput 'domain.txt') $taskOutput (Join-Path $taskOutput 'gpu.csv') *> (Join-Path $taskOutput 'gpu.log')
        if($LASTEXITCODE -ne 0){throw 'Layer GPU gate failed; inspect gpu.log'}
        if(Select-String -LiteralPath (Join-Path $taskOutput 'gpu.log') -Pattern 'VUID-|SYNC-HAZARD|Validation Error' -Quiet){throw 'Layer Vulkan validation report'}
        $taskHostRows=Import-Csv -LiteralPath (Join-Path $taskOutput 'host.csv');$taskGpuRows=Import-Csv -LiteralPath (Join-Path $taskOutput 'gpu.csv')
        if($taskHostRows.Count -ne 4096 -or $taskGpuRows.Count -ne 4096){throw 'Incomplete layer readbacks'}
        $taskMaxGapDelta=0.0
        for($taskIndex=0;$taskIndex -lt 4096;$taskIndex++) {
            if([int]$taskHostRows[$taskIndex].case -ne $taskIndex -or [int]$taskGpuRows[$taskIndex].case -ne $taskIndex){throw 'Layer case identity mismatch'}
            $taskDelta=[Math]::Abs([double]$taskHostRows[$taskIndex].gapBlocks-[double]$taskGpuRows[$taskIndex].gapBlocks)
            $taskMaxGapDelta=[Math]::Max($taskMaxGapDelta,$taskDelta)
        }
        if($taskMaxGapDelta -gt .00005){throw 'Layer CPU/GPU gap disagreement'}
    }
    [ordered]@{State=if($taskCapture){'captured-source-consistent'}elseif($Diagnose){'reproduced-discontinuity'}else{'continuous'};
        ContinuityQualified=(!$taskCapture -and !$Diagnose -and !$CpuOnly);SourceDomainQualified=([bool]$taskCapture -and !$CpuOnly);
        CapturedEdgesQualified=([bool]$taskCapture -and !$CpuOnly -and $taskCaptureInfo.kind -eq 'actual-provider-edge-pairs');
        CpuCases=4096;GpuExecuted=(!$CpuOnly);MaxBackendGapDelta=$taskMaxGapDelta;
        PrimitiveSha256=(Get-FileHash -LiteralPath (Join-Path $taskOutput 'water-layer-primitives.bin')).Hash;
        SourceHead=(& git rev-parse HEAD);SourceDirty=[bool](& git status --porcelain);
        Scope=if($taskCapture){'Actual provider mip/source inputs on CPU/Vulkan; wave gaps exclude rest-height differences; excludes active-ownership game optics or FPS'}else{'Synthetic coincident edges through production Java packing and Slang; excludes game-world optics and FPS'}
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOutput 'result.json') -Encoding utf8
    Write-Output "WATER_LAYER_RESULT=$taskOutput"
} finally {Pop-Location}
