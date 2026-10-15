import water;
import water_grazing_solver;
import world_core;
import world_common;

[[vk::binding(100,0)]] RWStructuredBuffer<float4> output;
[[vk::binding(101,0)]] StructuredBuffer<WaterWaveDomain> domains;
struct ProbePush { uint count; uint shuffled; uint reserved0; uint reserved1; };
[[vk::push_constant]] ConstantBuffer<ProbePush> settings;

[shader("compute")]
[numthreads(64,1,1)]
void waterGpuProbe(uint3 id:SV_DispatchThreadID) {
    if(id.x>=settings.count) return;
    uint c=id.x&4095u;
    if(settings.shuffled!=0) c=(c*757u)&4095u;
    uint fi=c&3u,side=(c>>2)&1u,sample=(c>>3)&3u,ti=(c>>5)&7u,ai=(c>>8)&7u,si=(c>>11)&1u;
    float angles[8]={0.25,0.5,1.0,2.0,4.0,8.0,15.0,30.0};
    float strength=si==0?1.0:4.0;
    worldPush.waterOpacity=float4(1.0,strength,1.0,7.0);
    worldPush.waterAnchor=float4(0.0);
    for(int i=0;i<WAVE_COUNT;i++) worldPush.waterWaveDomains[i]=domains[i];
    float theta=angles[ai]*PI/180.0,sign=side==0?1.0:-1.0;
    float3 rd=float3(cos(theta),-sign*sin(theta),0.0),n=float3(0.0,sign,0.0);
    float3 plane=float3(float(int(sample)*11-17),112.0,float(int(sample)*7-9));
    float hitT=(side==0?WAVE_MAX_HEIGHT*strength+0.5:0.5)/sin(theta);
    float3 ro=plane-rd*hitT;
    float time=float(ti)*1.37,footprint=fi==0?0.0:fi==1?0.5:fi==2?2.0:8.0;
    float t;uint work;bool exhausted=false;
#if GYSF_GPU_CANDIDATE
    t=strictWaveHit(ro,rd,hitT,n,time,footprint,work,exhausted);
#else
    WaterWaves waves=evaluateWaterWaves(ro,rd,hitT,n,time,time,footprint);
    t=waves.hitT;work=WAVE_NEWTON_STEPS;
#endif
    float3 p=ro+rd*t;
    float residual=abs(p.y-plane.y-waterWaveHeight(p.xz,time,footprint));
    output[id.x]=float4(t,residual,float(work),exhausted?1.0:0.0);
}
