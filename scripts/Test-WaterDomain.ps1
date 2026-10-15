[CmdletBinding()]
param([string]$SlangcPath,[switch]$Legacy)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if(-not $SlangcPath) { $SlangcPath=Join-Path $taskRoot '.tools/slang-2026.18/bin/slangc.exe' }
    $taskBuild=Join-Path $taskRoot 'build/shader-test'
    $taskJdk=if($env:JAVA_HOME){$env:JAVA_HOME}else{'C:/Program Files/Java/jdk-25'}
    $taskJava=Join-Path $taskJdk 'bin/java.exe'
    $taskJavac=Join-Path (Split-Path $taskJava) 'javac.exe'
    New-Item -ItemType Directory -Force (Join-Path $taskBuild 'classes') | Out-Null
    & $taskJavac -d (Join-Path $taskBuild 'classes') src/main/java/dev/comfyfluffy/caustica/rt/RtWaterWaveDomain.java src/main/java/dev/comfyfluffy/caustica/rt/RtWaterWaveClock.java src/shaderTest/WaterDomainFixtures.java
    if($LASTEXITCODE -ne 0) { throw 'Water-domain Java fixture compilation failed' }
    & $taskJava -cp (Join-Path $taskBuild 'classes') dev.comfyfluffy.caustica.rt.WaterDomainFixtures (Join-Path $taskBuild 'water-domain-fixtures.txt')
    if($LASTEXITCODE -ne 0) { throw 'Water-domain CPU transport failed' }
    $taskPhased=if($Legacy){0}else{1}
    & $SlangcPath src/shaderTest/water_domain.slang -I shaders/world -D "WATER_DOMAIN_PHASED=$taskPhased" -target cpp -o (Join-Path $taskBuild 'water-domain-generated.cpp')
    if($LASTEXITCODE -ne 0) { throw 'Slang host compilation failed' }
    & cmake -S src/shaderTest -B $taskBuild -A x64
    if($LASTEXITCODE -ne 0) { throw 'Shader-test CMake configure failed' }
    & cmake --build $taskBuild --config Release --target water_domain
    if($LASTEXITCODE -ne 0) { throw 'Water-domain host build failed' }
    $taskArgs=@((Join-Path $taskBuild 'water-domain-fixtures.txt'))
    if($Legacy) { $taskArgs+='--expect-legacy-mask' }
    & (Join-Path $taskBuild 'Release/water_domain.exe') @taskArgs
    if($LASTEXITCODE -ne 0) { throw 'Water-domain behavioural probe failed' }
} finally { Pop-Location }
