import water_patch;
import water;
import water_field;
import world_core;

// RtWaterGeometry: five std430 float4 lanes, 80 bytes per water triangle. v0.w is 0 (flat face),
// 1 (free top, v2.w constant source layer) or 2 (free top, v2.w missing fourth-corner height).
// v1.w is its projected half (including either quad diagonal). All other faces stay exact flat triangles.
public struct WaterPrimitive {
    public float4 v0;
    public float4 v1;
    public float4 v2;
    public float4 rectangle;
    public float4 shore;
};

public WaterPatch primitiveWaterPatch(WaterPrimitive primitive,float3 translation) {
    WaterPatch patch;
    patch.point=primitive.v0.xyz+translation;
    patch.normal=normalize(cross(primitive.v1.xyz-primitive.v0.xyz,primitive.v2.xyz-primitive.v0.xyz));
    patch.minimum=primitive.rectangle.xy+translation.xz;
    patch.extent=primitive.rectangle.zw;
    patch.shore=primitive.shore;
    patch.half=uint(primitive.v1.w);
    return patch;
}

public WaterSourceDomain primitiveWaterSource(WaterPrimitive primitive,float3 translation) {
    if(primitive.v0.w!=2)return constantWaterSource(worldPush.sharcGridOrigin.y+int(translation.y)+int(primitive.v2.w));
    WaterSourceDomain source;
    source.varying=true;source.layer=0;source.minimum=primitive.rectangle.xy+translation.xz;
    source.extent=primitive.rectangle.zw;source.originY=worldPush.sharcGridOrigin.y;
    float4 height=float4(primitive.v2.w);
    float3 vertices[3]={primitive.v0.xyz,primitive.v1.xyz,primitive.v2.xyz};
    for(int i=0;i<3;i++) {
        uint index=(vertices[i].x>primitive.rectangle.x?2u:0u)|(vertices[i].z>primitive.rectangle.y?1u:0u);
        height[index]=vertices[i].y;
    }
    source.height=height+translation.y;
    return source;
}

public WaterWaves primitiveWaterWaves(WaterPrimitive primitive,float3 translation,float3 position,
        float hitT,bool animated,float currentT,float previousT) {
    float3 restNormal=normalize(cross(primitive.v1.xyz-primitive.v0.xyz,primitive.v2.xyz-primitive.v0.xyz));
    WaterWaves waves=flatWaterWaves(position,hitT,restNormal);
    if(primitive.v0.w!=0 && animated) {
        WaterPatch patch=primitiveWaterPatch(primitive,translation);
        float height,previousHeight;float2 gradient,previousGradient;
        WaterSourceDomain source=primitiveWaterSource(primitive,translation);
        WaterPatch current=worldPush.waterFieldAddr==0?patch:
                canonicalWaterCell(patch,worldPush.waterFieldAddr,position.xz,source);
        WaterPatch previous=worldPush.waterPreviousFieldAddr==0?patch:
                canonicalWaterCell(patch,worldPush.waterPreviousFieldAddr,position.xz,source);
        patchField(current,position.xz,currentT,0.0,height,gradient);
        bool sameDomain=all(current.minimum==previous.minimum)&&all(current.extent==previous.extent)
                &&all(current.shore==previous.shore);
        bool samePhase=currentT==previousT||waterWaveSpeedScale()==0.0||waterWaveStrengthScale()==0.0;
        if(sameDomain&&samePhase) {
            previousHeight=height;previousGradient=gradient;
        } else {
            patchField(previous,position.xz,previousT,0.0,previousHeight,previousGradient);
        }
        waves.normal=waterSurfaceNormal(restNormal,gradient);
        waves.previousNormal=waterSurfaceNormal(restNormal,previousGradient);
        waves.motionPrev=float3(0,height-previousHeight,0);
        waves.geometricNormal=waves.normal;
    }
    return waves;
}

public float3 primitiveWaterOwnership(WaterPrimitive primitive,float3 translation,float3 displacedPosition) {
    float3 normal=cross(primitive.v1.xyz-primitive.v0.xyz,primitive.v2.xyz-primitive.v0.xyz);
    if(primitive.v0.w==0)return displacedPosition;
    float3 point=primitive.v0.xyz+translation;
    float y=point.y-dot(normal.xz,displacedPosition.xz-point.xz)/normal.y;
    return float3(displacedPosition.x,y,displacedPosition.z);
}

public float flatWaterTriangle(WaterPrimitive primitive,float3 translation,float3 ro,float3 rd,
        float rayMin,float rayMax,out float2 bary) {
    float3 a=primitive.v0.xyz+translation,e1=primitive.v1.xyz-primitive.v0.xyz,e2=primitive.v2.xyz-primitive.v0.xyz;
    float3 p=cross(rd,e2);float det=dot(e1,p);bary=float2(0);
    if(det==0)return -1;
    float3 s=ro-a,q=cross(s,e1);
    bary=float2(dot(s,p),dot(rd,q))/det;
    float t=dot(e2,q)/det;
    return all(bary>=0)&&bary.x+bary.y<=1&&t>=rayMin&&t<=rayMax?t:-1;
}

public float intersectWaterPrimitive(WaterPrimitive primitive,float3 translation,float3 ro,float3 rd,
        float time,float footprint,bool animated,float rayMin,float rayMax,
        out float2 bary,out uint work,out bool exhausted) {
    work=0;exhausted=false;
    if(primitive.v0.w==0||!animated)return flatWaterTriangle(primitive,translation,ro,rd,rayMin,rayMax,bary);
    WaterPatch patch=primitiveWaterPatch(primitive,translation);
    WaterSourceDomain source=primitiveWaterSource(primitive,translation);
    float t=worldPush.waterFieldAddr==0
        ? intersectWaterPatch(patch,ro,rd,time,footprint,rayMin,rayMax,work,exhausted)
        : intersectCanonicalWaterPatch(patch,source,ro,rd,time,footprint,rayMin,rayMax,work,exhausted);
    bary=float2(0);
    if(t>=0) {
        float2 a=primitive.v0.xz+translation.xz,e1=primitive.v1.xz-primitive.v0.xz,e2=primitive.v2.xz-primitive.v0.xz,p=(ro+rd*t).xz-a;
        float det=e1.x*e2.y-e1.y*e2.x;
        bary=float2(p.x*e2.y-p.y*e2.x,e1.x*p.y-e1.y*p.x)/det;
    }
    return t;
}
