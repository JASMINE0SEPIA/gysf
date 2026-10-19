import weather;
RWStructuredBuffer<uint> result;

[shader("compute")]
[numthreads(1,1,1)]
void weatherTransportProbe(uint3 id : SV_DispatchThreadID) {
    uint failures = 0, cases = 0;
    // A uniform integration grid is an independent oracle for the visible/background shares.
    // In particular, 3% coverage must transmit 97%, rather than fail a 0.1 hard cutoff.
    for (int level = 0; level <= 100; level++) {
        float opacity = float(level) / 100.0;
        for (int texel = 0; texel < 3; texel++) {
            float alpha = texel == 0 ? 0.0 : texel == 1 ? 0.5 : 1.0;
            uint hits = 0;
            for (uint sample = 0; sample < 4096; sample++)
                if (weatherCoverageAccept(alpha, opacity, (float(sample) + 0.5) / 4096.0)) hits++;
            if (abs(float(hits) / 4096.0 - alpha * opacity) > 1.0 / 4096.0) failures++;
            cases++;
        }
    }
    if (weatherCoverageAccept(0.0, 1.0, 0.0) || weatherCoverageAccept(1.0, 0.0, 0.0)) failures++;
    float3 day = float3(0.6, 0.65, 0.7), night = float3(0.01, 0.015, 0.02);
    if (any(weatherIllumination(float2(0,0), day, 1) != float3(0))) failures++;
    if (any(abs(weatherIllumination(float2(1,0), day, 1) - day) > 1e-6)) failures++;
    if (any(weatherIllumination(float2(1,0), night, 1) >= day)) failures++;
    if (any(weatherIllumination(float2(1,1), night, 0) != night)) failures++;
    cases += 4;
    float3 previous = float3(0);
    for (int level = 0; level <= 15; level++) {
        float3 lit = weatherIllumination(float2(1,float(level)/15.0), night, 1);
        if (any(lit < previous) || any(lit != lit)) failures++;
        previous = lit; cases++;
    }
    if (any(previous <= night) || previous.r <= previous.b) failures++;
    result[0] = failures; result[1] = cases;
}
