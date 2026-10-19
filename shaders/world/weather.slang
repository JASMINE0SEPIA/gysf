// Weather is translucent coverage, rather than an alpha-tested solid surface.
// Shared by the any-hit stage and the executable transport probe.
public bool weatherCoverageAccept(float textureAlpha, float columnOpacity, float threshold) {
    return threshold < clamp(textureAlpha * columnOpacity, 0.0, 1.0);
}

// Vanilla lightmap-style falloff; low block-light levels must not brighten an entire rain sheet.
float weatherLightLevel(float value) {
    float level = clamp(value, 0.0, 1.0);
    return level / (4.0 - 3.0 * level);
}

public float3 weatherIllumination(float2 skyBlockLevels, float3 skyAmbient, float blockLightScale) {
    float3 sky = max(skyAmbient, float3(0.0)) * weatherLightLevel(skyBlockLevels.x);
    float3 block = float3(1.0, 0.85, 0.65) * max(blockLightScale, 0.0)
            * weatherLightLevel(skyBlockLevels.y);
    return sky + block;
}
