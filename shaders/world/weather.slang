// Weather is translucent coverage, rather than an alpha-tested solid surface.
// Shared by the any-hit stage and the executable transport probe.
public bool weatherCoverageAccept(float textureAlpha, float columnOpacity, float threshold) {
    return threshold < clamp(textureAlpha * columnOpacity, 0.0, 1.0);
}
