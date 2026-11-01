#ifndef MULTIDRAW
#define MULTIDRAW 0
#endif

// These layouts match Minecraft 26.3's Globals, Projection, TerrainUniform and ChunkSection UBOs.
struct GlobalsData
{
    int3 CameraBlockPos; float GlintAlpha;
    float3 CameraOffset; float GameTime;
    float2 ScreenSize; int MenuBlurRadius; int UseRgss;
};
struct ProjectionData { float4x4 ProjMat; };
struct TerrainData { float4x4 ModelViewMat; int2 TextureSize; };
struct SectionData { int3 ChunkPosition; float ChunkVisibility; };
struct PreviewParameters { int mode; float alphaCutoff; float2 padding; };

ConstantBuffer<GlobalsData> Globals;
ConstantBuffer<ProjectionData> Projection;
ConstantBuffer<TerrainData> TerrainUniform;
ConstantBuffer<SectionData> ChunkSection;
Sampler2D<float4> Sampler0;
[[vk::push_constant]] ConstantBuffer<PreviewParameters> preview;

struct SurfaceVertex
{
    float4 position : SV_Position;
    [[vk::location(0)]] float3 sectionPosition : TEXCOORD0;
    [[vk::location(1)]] float2 uv : TEXCOORD1;
    [[vk::location(2)]] float4 materialTint : COLOR0;
};

[shader("vertex")]
SurfaceVertex vertexMain(
    [[vk::location(0)]] float3 Position : POSITION,
    [[vk::location(1)]] float4 Color : COLOR0,
    [[vk::location(2)]] float2 UV0 : TEXCOORD0
#if MULTIDRAW
    , [[vk::location(4)]] int3 ChunkPosition : TEXCOORD1
#endif
)
{
    SurfaceVertex output;
#if MULTIDRAW
    int3 origin = ChunkPosition;
#else
    int3 origin = ChunkSection.ChunkPosition;
#endif
    // Subtract integer positions before converting to float, as the host does.
    float3 cameraRelative = Position + float3(origin - Globals.CameraBlockPos) + Globals.CameraOffset;
    output.position = mul(Projection.ProjMat, mul(TerrainUniform.ModelViewMat, float4(cameraRelative, 1.0)));
    output.sectionPosition = Position;
    output.uv = UV0;
    output.materialTint = Color;
    return output;
}

[shader("fragment")]
float4 fragmentMain(SurfaceVertex input, bool front : SV_IsFrontFace) : SV_Target0
{
    float4 texel = Sampler0.Sample(input.uv);
    // Derive geometry normals within the actual primitive, never across depth-buffer edges.
    float3 normal = normalize(cross(ddx(input.sectionPosition), ddy(input.sectionPosition)));
    if (!front) normal = -normal;

    float3 axis = abs(normal);
    float2 facePosition = axis.y > axis.x && axis.y > axis.z ? input.sectionPosition.xz
        : axis.x > axis.z ? input.sectionPosition.zy : input.sectionPosition.xy;
    float scale = preview.mode == 4 ? 8.0 : 16.0;
    float2 grid = facePosition * scale;
    float2 footprint = max(fwidth(grid), 0.0001);
    float2 cell = frac(grid);
    float edge = min(min(cell.x, 1.0 - cell.x) / footprint.x, min(cell.y, 1.0 - cell.y) / footprint.y);
    float fade = 1.0 - smoothstep(0.35, 1.0, max(footprint.x, footprint.y));

    if (texel.a < preview.alphaCutoff) discard;
    if (preview.mode == 0) return float4(texel.rgb * input.materialTint.rgb, 1.0);
    if (preview.mode == 1) return float4(texel.rgb, 1.0); // Raw atlas color, without baked tint/AO or lightmap.
    if (preview.mode == 2) return float4(normal * 0.5 + 0.5, 1.0);
    if (preview.mode == 5) return float4(input.materialTint.rgb, 1.0); // Functional material color only; no biome tint, AO or directional shade.

    // Section origins are multiples of 16 blocks, so both grids have identical phase in every section.
    // No camera position, texture UV or quantized depth participates in grid placement.
    if (max(axis.x, max(axis.y, axis.z)) < 0.999) return float4(texel.rgb * float3(0.75, 0.85, 1.0), 1.0);
    float parity = fmod(abs(floor(grid.x) + floor(grid.y)), 2.0);
    float3 checker = texel.rgb * lerp(0.80, 1.0, parity);
    float3 color = lerp(texel.rgb, checker, fade);
    color = lerp(color, texel.rgb * 0.12, (1.0 - saturate(edge)) * fade);
    return float4(color, 1.0);
}
