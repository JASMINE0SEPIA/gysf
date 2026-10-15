[CmdletBinding()]
param([string]$SlangcPath,[string]$RayTable,[switch]$Scene,[string]$Geometry)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if(-not $SlangcPath) { $SlangcPath=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe' }
    $taskBuild=Join-Path $taskRoot 'build/shader-test'
    New-Item -ItemType Directory -Force $taskBuild | Out-Null
    if($RayTable) {
        $taskParent=Split-Path -Parent ([IO.Path]::GetFullPath($RayTable))
        New-Item -ItemType Directory -Force $taskParent | Out-Null
    }
    $taskScene=if($Scene){1}else{0}
    & $SlangcPath src/shaderTest/water_patch_probe.slang -I shaders/world -target cpp -D "GYSF_PATCH_SCENE=$taskScene" -o (Join-Path $taskBuild 'water-patch-generated.cpp')
    if($LASTEXITCODE -ne 0) { throw 'Finite-patch Slang compilation failed' }
    & cmake -S src/shaderTest -B $taskBuild -A x64
    if($LASTEXITCODE -ne 0) { throw 'Finite-patch CMake configure failed' }
    & cmake --build $taskBuild --config Release --target water_patch
    if($LASTEXITCODE -ne 0) { throw 'Finite-patch host build failed' }
    $taskArgs=@()
    if($RayTable) { $taskArgs+=@('--ray-table',$RayTable) }
    if($Geometry) { $taskArgs+=@('--geometry',(Resolve-Path -LiteralPath $Geometry).Path) }
    & (Join-Path $taskBuild 'Release/water_patch.exe') @taskArgs
    if($LASTEXITCODE -ne 0) { throw 'Finite-patch correctness gate failed' }
} finally { Pop-Location }
