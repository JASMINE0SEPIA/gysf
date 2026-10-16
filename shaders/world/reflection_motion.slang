import world_common;
import world_core;

// Reproject the reflected feature on both frames. Ray-origin bias moves its current mirror image
// away from the primary pixel, so subtracting that pixel invents motion even in a static scene.
public float2 reflectionFeatureNdc(float3 surface,float3 normal,float3 reflected,
        float4x4 projection,float3 cameraOffset,out bool valid) {
    float3 n=normalize(normal);
    float3 mirrored=reflected-2.0*n*dot(reflected-surface,n);
    float4 clip=mul(projection,float4(mirrored-cameraOffset,1.0));
    valid=clip.w>0.0;
    return valid?clip.xy/clip.w:float2(0);
}

public float2 reflectionMotionNdc(float3 surface,float3 normal,float3 previousNormal,
        float3 surfaceMotion,float3 reflected,float3 reflectedMotion,out bool valid) {
    bool currentValid,previousValid;
    float2 current=reflectionFeatureNdc(surface,normal,reflected,
            worldPush.curViewProj,worldPush.camOffset,currentValid);
    float2 previous=reflectionFeatureNdc(surface-surfaceMotion,previousNormal,reflected-reflectedMotion,
            worldPush.prevViewProj,worldPush.camOffset-worldPush.camDelta,previousValid);
    valid=currentValid&&previousValid;
    return valid?previous-current:float2(0);
}
