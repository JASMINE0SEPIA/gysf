import water;
import water_patch;
import water_patch_cases;
import world_core;
import world_common;

[[vk::binding(100,0)]] RWStructuredBuffer<float4> output;
[[vk::binding(101,0)]] StructuredBuffer<WaterWaveDomain> domains;
struct ProbePush { uint count; uint shuffled; uint reserved0; uint reserved1; };
[[vk::push_constant]] ConstantBuffer<ProbePush> settings;

[shader("compute")]
[numthreads(64,1,1)]
void waterGpuProbe(uint3 id:SV_DispatchThreadID) {
    if(id.x>=settings.count)return;
    uint c=id.x&4095u;if(settings.shuffled!=0)c=(c*757u)&4095u;
    worldPush.waterAnchor=float4(0);
    for(int i=0;i<WAVE_COUNT;i++)worldPush.waterWaveDomains[i]=domains[i];
    WaterPatch patch;float3 ro,rd;float time,footprint;
    waterPatchCase(c,patch,ro,rd,time,footprint);
    float t=-1;uint work=0;bool exhausted=false;
#if GYSF_GPU_CANDIDATE
    t=intersectWaterPatch(patch,ro,rd,time,footprint,.0001,100,work,exhausted);
#else
    // Diagnostic negative control: the current runtime receives a rest-plane triangle hit before
    // refining an unbounded height field. Keep the finite triangle gate on that *rest* hit only.
    // Horizontal rays and origins inside crests expose why changing Newton alone is insufficient.
    float rate=dot(patch.normal,rd),planeT=-dot(patch.normal,ro-patch.point)/rate;
    if(rate!=0 && planeT>=.0001 && planeT<=100) {
        float2 uv=((ro+rd*planeT).xz-patch.minimum)/patch.extent;
        if(all(uv>=0)&&all(uv<=1)&&(patch.half==0?uv.x>=uv.y:uv.y>=uv.x)) {
            float3 normal=rate<0?patch.normal:-patch.normal;
            t=evaluateWaterWaves(ro,rd,planeT,normal,time,time,footprint).hitT;
            work=WAVE_NEWTON_STEPS;
        }
    }
#endif
    float residual=0;
    if(t>=0){float derivative;residual=abs(patchEquation(patch,ro,rd,t,time,footprint,derivative));}
    output[id.x]=float4(t,residual,float(work),exhausted?1.0:0.0);
}
