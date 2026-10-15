import world_common;
import math;

// The packed face owns a voxel even when its fluid triangle slopes across that voxel. Using its
// axis rather than the triangle normal keeps the ownership probe out of lateral neighbours.
public float3 voxyOwnershipNormal(uint flags) {
    uint face = flags & 7u;
    float sign = (face & 1u) != 0u ? 1.0 : -1.0;
    return face < 2u ? float3(0.0, sign, 0.0)
            : face < 4u ? float3(0.0, 0.0, sign) : float3(sign, 0.0, 0.0);
}

public float2 voxyUv(Section section, uint primitive, float3 bary) {
    ConstPtr<float2> uv = ConstPtr<float2>(section.uvAddr);
    return bary.x * uv[primitive * 3u] + bary.y * uv[primitive * 3u + 1u] + bary.z * uv[primitive * 3u + 2u];
}

// Model coordinates address six independent faces in a 256x256-model atlas. Deriving the tile size
// from the actual image preserves mip-tail fallback atlases as well as full-resolution images.
public float4 voxyTexel(TerrainPrim primitive, float2 uv, uint rayCone, float hitT, Sampler2D texture) {
    uint width, height;
    texture.GetDimensions(width, height);
    float tileSize = float(width) / (256.0 * 3.0);
    float2 cone = unpackHalf2(rayCone);
    float footprint = max(cone.x + cone.y * max(hitT, 0.0), 1.0e-5) * tileSize / float(1u << primitive.aux1);
    float lod = clamp(log2(max(footprint, 1.0e-4)), 0.0, max(0.0, log2(tileSize) - 1.0));
    float margin = min(0.5, exp2(lod) / (2.0 * tileSize));
    float2 local = clamp(frac(uv), float2(margin), float2(1.0 - margin));
    uint face = primitive.flags & 7u;
    float2 model = float2(primitive.aux0 & 255u, primitive.aux0 >> 8);
    float2 faceTile = float2(face >> 1, face & 1u);
    float2 atlasUv = (model * float2(3.0, 2.0) + faceTile + local) * tileSize / float2(width, height);
    return texture.SampleLevel(atlasUv, lod);
}

public float3 voxyColour(TerrainPrim primitive, float4 texel) {
    uint mode = (primitive.flags >> 3) & 3u;
    bool tinted = mode == 2u || (mode == 1u && abs(texel.r - texel.g) < 0.02 && abs(texel.g - texel.b) < 0.02);
    return srgbToLinear(texel.rgb) * (tinted ? primitive.tint.rgb : float3(1.0));
}

public float voxyGlassAlpha(float alpha) {
    return 1.0 - sqrt(1.0 - clamp(alpha, 0.06, 0.72));
}
