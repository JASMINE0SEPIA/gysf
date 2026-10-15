import water;
import water_patch;
import water_patch_cases;
import world_core;
import world_common;

[[vk::binding(100,0)]] RWStructuredBuffer<float4> output;
[[vk::binding(101,0)]] StructuredBuffer<WaterWaveDomain> domains;
[[vk::binding(102,0)]] RaytracingAccelerationStructure patchScene;
struct ProbePush { uint count; uint shuffled; uint reserved0; uint reserved1; };
[[vk::push_constant]] ConstantBuffer<ProbePush> settings;
struct PatchPayload { float4 result; };
struct PatchAttributes { float residual; float work; };

uint patchCaseIndex() {
    uint c=DispatchRaysIndex().x&4095u;
    return settings.shuffled!=0?(c*757u)&4095u:c;
}

[shader("raygeneration")]
void patchRaygen() {
    uint index=DispatchRaysIndex().x;if(index>=settings.count)return;
    uint c=patchCaseIndex();WaterPatch patch;float3 ro,rd;float time,footprint;
    waterPatchCase(c,patch,ro,rd,time,footprint);
    RayDesc ray;ray.Origin=ro;ray.Direction=rd;ray.TMin=.0001;ray.TMax=100;
    // Eight mask bits isolate each fixture triangle. The unmasked multi-patch scene is a separate
    // integration requirement: this test verifies a selected patch survives real AABB traversal.
    uint mask=settings.reserved0!=0?255u:1u<<((((c>>3)&3u)*2u)+patch.half);
    PatchPayload p;p.result=float4(-1,0,0,0);
    TraceRay(patchScene,RAY_FLAG_FORCE_OPAQUE,mask,0,0,0,ray,p);
    output[index]=p.result;
}

[shader("intersection")]
void patchIntersection() {
    uint c=patchCaseIndex();WaterPatch patch;float3 unusedRo,unusedRd;float time,footprint;
    waterPatchCase(c,patch,unusedRo,unusedRd,time,footprint);
    patch=waterPatchGeometry(InstanceID()/2u,InstanceID()&1u);
    worldPush.waterAnchor=float4(0);
    for(int i=0;i<WAVE_COUNT;i++)worldPush.waterWaveDomains[i]=domains[i];
    float3 ro=ObjectRayOrigin(),rd=ObjectRayDirection();
    float t=-1;uint work=0;bool exhausted=false;
#if GYSF_GPU_CANDIDATE
    t=intersectWaterPatch(patch,ro,rd,time,footprint,RayTMin(),RayTCurrent(),work,exhausted);
#else
    float rate=dot(patch.normal,rd);
    if(rate!=0) {
        float planeT=-dot(patch.normal,ro-patch.point)/rate;
        float2 uv=((ro+rd*planeT).xz-patch.minimum)/patch.extent;
        if(planeT>=RayTMin()&&planeT<=RayTCurrent()&&all(uv>=0)&&all(uv<=1)
                &&(patch.half==0?uv.x>=uv.y:uv.y>=uv.x)) {
            t=evaluateWaterWaves(ro,rd,planeT,rate<0?patch.normal:-patch.normal,time,time,footprint).hitT;
            work=WAVE_NEWTON_STEPS;
        }
    }
#endif
    if(t>=RayTMin()&&t<=RayTCurrent()) {
        float derivative;PatchAttributes attr;
        attr.residual=abs(patchEquation(patch,ro,rd,t,time,footprint,derivative));attr.work=float(work);
        ReportHit(t,0,attr);
    }
}

[shader("closesthit")]
void patchClosest(inout PatchPayload p,in PatchAttributes attr) {
    p.result=float4(RayTCurrent(),attr.residual,attr.work,0);
}

[shader("miss")]
void patchMiss(inout PatchPayload p) { p.result=float4(-1,0,0,0); }
