// Matrices are column-major, matching JOML's get(ByteBuffer) and the build's Slang option.
struct DepthParameters
{
    float4x4 inverseProjection;
    float4 viewport; // width, height, display range in blocks, unused
};

[[vk::push_constant]] ConstantBuffer<DepthParameters> parameters;
[[vk::binding(0, 0)]] Sampler2D<float> WorldDepth;

[shader("vertex")]
float4 vertexMain(uint vertex : SV_VertexID) : SV_Position
{
    float2 corner = float2((vertex << 1) & 2, vertex & 2);
    return float4(corner * 2.0 - 1.0, 0.0, 1.0);
}

[shader("fragment")]
float4 fragmentMain(float4 pixel : SV_Position) : SV_Target0
{
    float depth = WorldDepth.Load(int3(int2(pixel.xy), 0));
    // Minecraft uses reversed Z: clear depth zero is the sky, rather than the near plane.
    if (depth == 0.0)
        return float4(0.055, 0.11, 0.20, 1.0);

    // Match the host's positive-height Vulkan viewport, before its final presentation transform.
    float2 ndc = pixel.xy / parameters.viewport.xy * 2.0 - 1.0;
    float4 view = mul(parameters.inverseProjection, float4(ndc, depth, 1.0));
    float distance = length(view.xyz / view.w);
    float brightness = saturate(1.0 - log2(1.0 + distance) / log2(1.0 + parameters.viewport.z));
    return float4(brightness.xxx, 1.0);
}
