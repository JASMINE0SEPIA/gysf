import world_common;
import world_core;
import water;
import water_primitive;
import water_field;
import water_test_frame;

[[vk::binding(100,0)]] RWStructuredBuffer<float4> layerResults;
[[vk::binding(101,0)]] StructuredBuffer<uint64_t> layerAddresses;

uint capturedWord(uint64_t address,uint index) {
    __target_switch {
    case cpp: __intrinsic_asm "((const uint32_t*)($0))[$1]";
    default: return ConstPtr<uint>(address)[index];
    }
}
float4 capturedLane(uint index) {
    uint64_t address=layerAddresses[2];
    return asfloat(uint4(capturedWord(address,index),capturedWord(address,index+1),capturedWord(address,index+2),capturedWord(address,index+3)));
}
WaterPrimitive capturedPrimitive(uint index) {
    WaterPrimitive p;uint base=index*20;
    p.v0=capturedLane(base);p.v1=capturedLane(base+4);p.v2=capturedLane(base+8);
    p.rectangle=capturedLane(base+12);p.shore=capturedLane(base+16);return p;
}
float root(WaterPrimitive p,float3 ro,float time,bool animated,out uint failed) {
    float2 bary;uint work;bool exhausted;
    float t=intersectWaterPrimitive(p,float3(0),ro,float3(0,-1,0),time,0,animated,0,100,bary,work,exhausted);
    failed=(exhausted||t<0)?1:0;return t;
}
[shader("compute")]
[numthreads(64,1,1)]
void fieldProbe(uint3 id:SV_DispatchThreadID) {
    uint c=id.x;if(c>=4096)return;
    worldPush.waterFieldAddr=layerAddresses[0];worldPush.waterPreviousFieldAddr=layerAddresses[1];
    worldPush.sharcGridOrigin=int4(0,128,0,0);setWaterTestDomain(float2(0));
    worldPush.waterOpacity=float4(0,(c&1)==0?1:4,1,7);
    WaterPrimitive a=capturedPrimitive(c*3),b=capturedPrimitive(c*3+1),reference=capturedPrimitive(c*3+2);
    float3 ro=float3(reference.v0.x,80,reference.v0.z);float time=float(c%61)*.317,previous=time-1.0/60.0;
    bool separate=reference.v1.w!=0;
    float weight=(c&1)==0?reference.v1.x:reference.v1.y;
    float weightB=separate?((c&1)==0?reference.v2.x:reference.v2.y):weight;
    float restB=separate?reference.v0.w:reference.v0.y;
    float height=waterWaveHeight(ro.xz,time,0)*weight;
    float expected=80-reference.v0.y-height;
    float expectedB=80-restB-waterWaveHeight(ro.xz,time,0)*weightB;
    uint fa,fb,failures=0;
    float ta=root(a,ro,time,true,fa),tb=root(b,ro,time,true,fb);failures+=fa+fb;
    // Separate the measured displacement disagreement from already nonidentical rest meshes.
    float gap=abs((80-reference.v0.y-ta)-(80-restB-tb)),error=max(abs(ta-expected),abs(tb-expectedB));
    WaterWaves wa=primitiveWaterWaves(a,float3(0),ro+float3(0,-ta,0),ta,true,time,previous);
    WaterWaves wb=primitiveWaterWaves(b,float3(0),ro+float3(0,-tb,0),tb,true,time,previous);
    float motion=(waterWaveHeight(ro.xz,time,0)-waterWaveHeight(ro.xz,previous,0))*weight;
    float motionB=(waterWaveHeight(ro.xz,time,0)-waterWaveHeight(ro.xz,previous,0))*weightB;
    error=max(error,max(abs(wa.motionPrev.y-motion),abs(wb.motionPrev.y-motionB)));
    float flatA=root(a,ro,time,false,fa),flatB=root(b,ro,time,false,fb);failures+=fa+fb;
    error=max(error,max(abs(flatA-(80-reference.v0.y)),abs(flatB-(80-restB))));
    // Rebase these unchanged actual packets into explicitly unvisited provider source tiles.
    worldPush.sharcGridOrigin.xz=int2(65536);worldPush.waterFieldAddr=layerAddresses[3];
    int2 node=int2(floor(ro.xz));
    int sourceY=waterSourceLayer(primitiveWaterSource(a,float3(0)),float2(node));
    if((waterNodeWord(worldPush.waterFieldAddr,worldPush.sharcGridOrigin.xz+node,sourceY)&65536u)!=0)failures++;
    float unknownA=root(a,ro,time,true,fa),unknownB=root(b,ro,time,true,fb);failures+=fa+fb;
    error=max(error,max(abs(unknownA-flatA),abs(unknownB-flatB)));
    worldPush.sharcGridOrigin.xz=int2(0);
    worldPush.waterFieldAddr=0;
    float wetA=root(a,ro,time,true,fa),wetB=root(b,ro,time,true,fb);failures+=fa+fb;
    float wet=80-reference.v0.y-waterWaveHeight(ro.xz,time,0);
    float wetBExpected=80-restB-waterWaveHeight(ro.xz,time,0);
    error=max(error,max(abs(wetA-wet),abs(wetB-wetBExpected)));
    if(!isfinite(gap)||!isfinite(error)||gap>.00005||error>.00005)failures++;
    layerResults[c]=float4(float(failures),gap,error,exp2(reference.v1.z));
}
