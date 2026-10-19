[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$testRoot=Join-Path (Split-Path -Parent $PSScriptRoot) 'build/validation-log-tests'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$clean="Synthetic checker fixture`nGYSF_SMOKE: PASS`nRT LOD native shutdown: live=0 bytes"
$streaming="$clean`nGYSF_LOD_REENTRY: PASS cold=true far=4954 resident=5349 publishedFar=4000 ingest=false frames=1200`nGYSF_LOD_STREAMING: PASS cancelled=0 pending=false distance=0 inFlight=0 peak=16 remaining=0`nGYSF_LOD_TURN: PASS {viewCancellations=0, retargets=11}"
$guides="$clean`nGYSF_WATER_GUIDES: PASS phases=4 locked=true jitter=0 staticWaves=true"+
    ("`nGYSF_GUIDE_CAPTURE: saved=x"*4)+"`nGuide readback shutdown: live=0 bytes, peak=64 bytes, captures=4"
$optics="$clean`nGYSF_WATER_GUIDES: PASS phases=8 locked=true jitter=0 staticWaves=true"+
    ("`nGYSF_GUIDE_CAPTURE: saved=x"*8)+"`nGuide readback shutdown: live=0 bytes, peak=64 bytes, captures=8"
$cases=@(
    @{Name='streaming-qualified';Text=$streaming;Reject=$false;Streaming=$true;Turns=$true},
    @{Name='streaming-missing';Text=$clean;Reject=$true;Streaming=$true},
    @{Name='streaming-cancelled';Text=$streaming.Replace('cancelled=0','cancelled=1');Reject=$true;Streaming=$true},
    @{Name='streaming-pending';Text=$streaming.Replace('pending=false','pending=true');Reject=$true;Streaming=$true},
    @{Name='streaming-distance';Text=$streaming.Replace('distance=0','distance=512');Reject=$true;Streaming=$true},
    @{Name='streaming-inflight';Text=$streaming.Replace('inFlight=0','inFlight=1');Reject=$true;Streaming=$true},
    @{Name='streaming-serial';Text=$streaming.Replace('peak=16','peak=1');Reject=$true;Streaming=$true},
    @{Name='streaming-unfinished';Text=$streaming.Replace('remaining=0','remaining=1');Reject=$true;Streaming=$true},
    @{Name='turns-missing';Text=$clean;Reject=$true;Turns=$true},
    @{Name='turns-cancelled';Text=$streaming.Replace('viewCancellations=0','viewCancellations=1');Reject=$true;Turns=$true},
    @{Name='weather-qualified';Text="GYSF_WEATHER: PASS captures=7 columns=441 rain=0.0..1.0 frames=1000`nRT LOD native shutdown: live=0 bytes";Reject=$false;Weather=$true},
    @{Name='weather-incomplete';Text="GYSF_WEATHER: PASS captures=6 columns=441 rain=0.0..1.0 frames=1000`nRT LOD native shutdown: live=0 bytes";Reject=$true;Weather=$true},
    @{Name='weather-dry';Text="GYSF_WEATHER: PASS captures=7 columns=0 rain=0.0..1.0 frames=1000`nRT LOD native shutdown: live=0 bytes";Reject=$true;Weather=$true},
    @{Name='weather-no-transition';Text="GYSF_WEATHER: PASS captures=7 columns=441 rain=1.0..1.0 frames=1000`nRT LOD native shutdown: live=0 bytes";Reject=$true;Weather=$true},
    @{Name='weather-validation';Text="GYSF_WEATHER: PASS captures=7 columns=441 rain=0.0..1.0 frames=1000`nRT LOD native shutdown: live=0 bytes`nSYNC-HAZARD";Reject=$true;Weather=$true},
    @{Name='clean';Text=$clean;Reject=$false},
    @{Name='ngx-clear-no-vuid';Text="$clean`nvkCmdClearColorImage(): WRITE_AFTER_WRITE hazard detected. nv.ngx.dlssd.resource";Reject=$true},
    @{Name='missing-pass';Text='RT LOD native shutdown: live=0 bytes';Reject=$true},
    @{Name='live-resources';Text='GYSF_SMOKE: PASS';Reject=$true},
    @{Name='mixed-shutdowns';Text="$clean`nRT LOD native shutdown: live=128 bytes";Reject=$true},
    @{Name='field-shutdown';Text="$clean`nWater field GPU shutdown: live=16 bytes";Reject=$true},
    @{Name='field-required';Text=$clean;Reject=$true;Field=$true},
    @{Name='field-qualified';Text="$clean`nGYSF_WATER_FIELD: PASS`nWater field GPU shutdown: live=0 bytes";Reject=$false;Field=$true},
    @{Name='guide-leak';Text="$clean`nGuide readback shutdown: live=64 bytes, peak=64 bytes, captures=4";Reject=$true},
    @{Name='guides-required';Text=$clean;Reject=$true;Guides=$true},
    @{Name='guides-incomplete';Text="$clean`nGuide readback shutdown: live=0 bytes, peak=64 bytes, captures=3";Reject=$true;Guides=$true},
    @{Name='guides-qualified';Text=$guides;Reject=$false;Guides=$true},
    @{Name='optics-wrong-count';Text=$optics;Reject=$true;Guides=$true},
    @{Name='optics-qualified';Text=$optics;Reject=$false;Guides=$true;GuideCount=8},
    @{Name='timing-required';Text=$clean;Reject=$true;Timings=$true},
    @{Name='timing-live-pool';Text="$clean`nGYSF_GPU_TIMINGS: complete samples=5 skipped=0 livePools=1";Reject=$true},
    @{Name='timing-uninitialized';Text="$clean`nGYSF_GPU_TIMINGS: complete samples=5 skipped=0 livePools=0";Reject=$true;Timings=$true},
    @{Name='timing-qualified';Text="$clean`nGYSF_GPU_TIMINGS: start timestampBits=64`nGYSF_GPU_TIMINGS: complete samples=5 skipped=0 livePools=0";Reject=$false;Timings=$true},
    @{Name='cache-required';Text=$clean;Reject=$true;Cache=$true},
    @{Name='cache-unpublished';Text="$clean`nWater bounds cache shutdown: live=0 bytes, peak=64 bytes, inputs=0 bytes, sources=0, pending=0, published=0";Reject=$true;Cache=$true},
    @{Name='cache-qualified';Text="$clean`nWater bounds cache shutdown: live=0 bytes, peak=64 bytes, inputs=0 bytes, sources=0, pending=0, published=2";Reject=$false;Cache=$true},
    @{Name='cache-native-leak';Text="$clean`nWater bounds cache shutdown: live=1 bytes, peak=64 bytes, inputs=0 bytes, sources=0, pending=0, published=2";Reject=$true},
    @{Name='cache-input-leak';Text="$clean`nWater bounds cache shutdown: live=0 bytes, peak=64 bytes, inputs=1 bytes, sources=0, pending=0, published=2";Reject=$true},
    @{Name='cache-source-leak';Text="$clean`nWater bounds cache shutdown: live=0 bytes, peak=64 bytes, inputs=0 bytes, sources=1, pending=0, published=2";Reject=$true},
    @{Name='cache-pending-leak';Text="$clean`nWater bounds cache shutdown: live=0 bytes, peak=64 bytes, inputs=0 bytes, sources=0, pending=1, published=2";Reject=$true}
)
foreach($case in $cases) {
    $casePath=Join-Path $testRoot ($case.Name+'.log')
    Set-Content -LiteralPath $casePath -Value $case.Text -Encoding utf8
    $rejected=$false
    $taskGuideCount=if($case.GuideCount){$case.GuideCount}else{4}
    try { & (Join-Path $PSScriptRoot 'Assert-VulkanSmokeLog.ps1') -Path $casePath -RequireWeather:([bool]$case.Weather) -RequireWaterField:([bool]$case.Field) -RequireWaterGuides:([bool]$case.Guides) -GuideCaptureCount $taskGuideCount -RequireGpuTimings:([bool]$case.Timings) -RequireWaterBoundsCache:([bool]$case.Cache) -RequireLodStreaming:([bool]$case.Streaming) -RequireLodTurns:([bool]$case.Turns) | Out-Null }
    catch {$rejected=$true}
    if($rejected -ne $case.Reject) {throw "Smoke log checker regression: $($case.Name)"}
}
Write-Output "Vulkan smoke log checker: $($cases.Count) cases passed"
