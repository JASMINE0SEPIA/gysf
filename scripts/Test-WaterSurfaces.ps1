[CmdletBinding()]
param([string]$SlangcPath)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if(-not $SlangcPath) { $SlangcPath=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe' }
    $taskBuild=Join-Path $taskRoot 'build/shader-test'
    New-Item -ItemType Directory -Force $taskBuild | Out-Null
    & $SlangcPath src/shaderTest/water_surface.slang -I shaders/world -target cpp -o (Join-Path $taskBuild 'water-surface-generated.cpp')
    if($LASTEXITCODE -ne 0) { throw 'Slang host compilation failed' }
    & cmake -S src/shaderTest -B $taskBuild -A x64
    if($LASTEXITCODE -ne 0) { throw 'Shader-test CMake configure failed' }
    & cmake --build $taskBuild --config Release --target water_surface
    if($LASTEXITCODE -ne 0) { throw 'Water-surface host build failed' }
    & (Join-Path $taskBuild 'Release/water_surface.exe')
    if($LASTEXITCODE -ne 0) { throw 'Water-surface behavioural probe failed' }
} finally { Pop-Location }
