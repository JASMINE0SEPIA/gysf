// Execute the production Slang water-origin function on the host backend. No Java math mirror.
import water;
RWStructuredBuffer<uint> result;

[shader("compute")]
[numthreads(1,1,1)]
void waterOriginProbe(uint3 id : SV_DispatchThreadID) {
    uint failures = 0u;
    uint cases = 0u;
    const float bias = 0.001;
    for (int side = 0; side < 2; ++side) {
        float3 normal = float3(0.0, side == 0 ? -1.0 : 1.0, 0.0);
        for (int height = -32; height <= 32; ++height) {
            WaterWaves waves = flatWaterWaves(float3(2.0, 112.0, -32.0), 1.0, normal);
            waves.position.y += float(height) / 64.0;
            for (int slope = -8; slope <= 8; ++slope) {
                if (slope == 0) continue;
                float3 rd = normalize(float3(1.0, float(slope) / 32.0, 0.3));
                float3 origin = waterWaveContinueOrigin(waves, rd, bias);
                float sign = dot(normal, rd) > 0.0 ? 1.0 : -1.0;
                // The outgoing ray must start beyond both planes; neither plane may be re-hit ahead.
                float restSide = sign * dot(normal, origin - waves.planePos);
                float waveSide = sign * dot(normal, origin - waves.position);
                if (restSide < bias - 1.0e-5 || waveSide < bias - 1.0e-5
                        || any(!isfinite(origin))) failures++;
                cases++;
            }
        }
    }
    // Underwater total internal reflection at a crest: ordinary displaced-point bias re-hits the
    // undeformed BLAS mesh ahead of the reflected ray, while the wave-aware origin cannot.
    float3 incoming = normalize(float3(1.0, 0.1, 0.0));
    float3 n = float3(0.0, -1.0, 0.0);
    float3 reflected = reflect(incoming, n);
    float3 transmitted = refract(incoming, n, 1.333);
    WaterWaves crest = flatWaterWaves(float3(0.0), 1.0, n);
    crest.position.y = 0.3;
    float3 oldOrigin = crest.position + n * bias;
    float3 origin = waterWaveContinueOrigin(crest, reflected, bias);
    float oldHitT = -oldOrigin.y / reflected.y;
    float hitT = -origin.y / reflected.y;
    bool reproduces = dot(transmitted, transmitted) == 0.0 && oldHitT > 0.0 && hitT < 0.0;
    if (!reproduces) failures++;
    result[0] = failures;
    result[1] = cases;
    result[2] = reproduces ? 1u : 0u;
}
