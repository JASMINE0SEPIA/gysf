import world_core;
import world_common;
import water;
import water_primitive;

[shader("intersection")]
void main() {
    worldPush=ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    bool lod=(InstanceID()&DH_INSTANCE_KIND)==DH_INSTANCE_KIND;
    Section section=ConstPtr<Section>(lod ? pc.dhTableAddr : pc.tableAddr)[InstanceID()&IDX_MASK];
    WaterPrimitive primitive=ConstPtr<WaterPrimitive>(section.waterAddr)[PrimitiveIndex()];
    float3x4 transform=ObjectToWorld3x4();
    float3 translation=float3(transform[0][3],transform[1][3],transform[2][3]);
    float2 bary;uint work;bool exhausted;
    // Geometry is shared by camera, optical, shadow and guide rays. Filtered shading must not
    // redefine which physical surface these rays intersect.
    float t=intersectWaterPrimitive(primitive,translation,WorldRayOrigin(),WorldRayDirection(),
            worldPush.waterAnchor.x,0.0,waterWavesEnabled(),RayTMin(),RayTCurrent(),bary,work,exhausted);
    if(t>=0) {
        BuiltInTriangleIntersectionAttributes attributes;
        attributes.barycentrics=bary;
        ReportHit(t,0u,attributes);
    }
}
