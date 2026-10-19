[CmdletBinding()]
param([string]$SlangcPath)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if(!$SlangcPath){$SlangcPath=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe'}
    $taskBuild=Join-Path $taskRoot 'build/shader-test'
    New-Item -ItemType Directory -Force $taskBuild|Out-Null
    & $SlangcPath src/shaderTest/weather_transport.slang -I shaders/world -target cpp -o (Join-Path $taskBuild 'weather-transport-generated.cpp')
    if($LASTEXITCODE){throw 'Weather Slang compilation failed'}
    & cmake -S src/shaderTest -B $taskBuild -A x64
    if($LASTEXITCODE){throw 'Weather test configure failed'}
    & cmake --build $taskBuild --config Release --target weather_transport
    if($LASTEXITCODE){throw 'Weather host build failed'}
    & (Join-Path $taskBuild 'Release/weather_transport.exe')
    if($LASTEXITCODE){throw 'Weather transport probe failed'}
} finally { Pop-Location }
