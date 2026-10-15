[CmdletBinding()]
param([string]$SlangcPath,[switch]$Prototype,[switch]$FullReference,[string]$RayTable)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if(-not $SlangcPath) { $SlangcPath=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe' }
    $taskBuild=Join-Path $taskRoot 'build/shader-test'
    New-Item -ItemType Directory -Force $taskBuild | Out-Null
    $taskPrototype=if($Prototype){1}else{0}
    if($FullReference -and -not $Prototype) { throw 'FullReference requires Prototype' }
    $taskFull=if($FullReference){1}else{0}
    & $SlangcPath src/shaderTest/water_grazing.slang -I shaders/world -D "GYSF_GRAZING_PROTOTYPE=$taskPrototype" -D "GYSF_GRAZING_FULL_REFERENCE=$taskFull" -target cpp -o (Join-Path $taskBuild 'water-grazing-generated.cpp')
    if($LASTEXITCODE -ne 0) { throw 'Slang host compilation failed' }
    & cmake -S src/shaderTest -B $taskBuild -A x64
    if($LASTEXITCODE -ne 0) { throw 'Shader-test CMake configure failed' }
    & cmake --build $taskBuild --config Release --target water_grazing
    if($LASTEXITCODE -ne 0) { throw 'Water-grazing host build failed' }
    $taskArgs=@()
    if($Prototype) { $taskArgs+='--prototype' }
    if($FullReference) { $taskArgs+='--full-reference' }
    if($RayTable) { $taskArgs+=@('--ray-table',$RayTable) }
    & (Join-Path $taskBuild 'Release/water_grazing.exe') @taskArgs
    if($LASTEXITCODE -ne 0) { throw 'Grazing residual diagnostic failed; see the unresolved production limitation in docs/VALIDATION-2026-10-15.md' }
} finally { Pop-Location }
