import world_common;
import world_core;
import water;
import water_primitive;
import water_test_frame;

#ifndef EXPECT_CONTINUOUS
#define EXPECT_CONTINUOUS 1
#endif

[[vk::binding(100,0)]] RWStructuredBuffer<float4> layerResults;
[[vk::binding(101,0)]] StructuredBuffer<uint64_t> layerAddresses;

uint packedWord(uint64_t address,uint index) {
    __target_switch {
    case cpp:
        __intrinsic_asm "((const uint32_t*)($0))[$1]";
    default:
        return ConstPtr<uint>(address)[index];
    }
}
float4 lane(uint index) {
    uint64_t address=layerAddresses[2];
    return asfloat(uint4(packedWord(address,index),packedWord(address,index+1),packedWord(address,index+2),packedWord(address,index+3)));
}
WaterPrimitive primitive(uint index) {
    WaterPrimitive p;uint base=index*20;
    p.v0=lane(base);p.v1=lane(base+4);p.v2=lane(base+8);p.rectangle=lane(base+12);p.shore=lane(base+16);
    return p;
}
float root(WaterPrimitive p,float3 ro,float time,bool animated,out bool failed) {
    float2 bary;uint work;bool exhausted;
    float t=intersectWaterPrimitive(p,float3(0),ro,float3(0,-1,0),time,0,animated,0,20,bary,work,exhausted);
    failed=exhausted||t<0;return t;
}

[shader("compute")]
[numthreads(64,1,1)]
void fieldProbe(uint3 id:SV_DispatchThreadID) {
    uint c=id.x;if(c>=4096)return;
    worldPush.waterFieldAddr=layerAddresses[0];worldPush.waterPreviousFieldAddr=layerAddresses[1];
    worldPush.waterOpacity=float4(0,(c&1)==0?1:4,1,7);
    int anchorY=(int((c/90)%3)-1)*128;
    worldPush.sharcGridOrigin=int4(0,anchorY,0,0);setWaterTestDomain(float2(0));
    WaterPrimitive a=primitive(c*2),b=primitive(c*2+1);
    float3 ro=float3(32,180-anchorY,16+float((c/10)%9)/8.0);
    float time=float(c%61)*.317;
    bool fa,fb;
    float ta=root(a,ro,time,true,fa),tb=root(b,ro,time,true,fb);
    uint failures=fa||fb?1:0;
    float gap=abs(ta-tb),expected=waterWaveHeight(ro.xz,time,0);
    float flatA=root(a,ro,time,false,fa),flatB=root(b,ro,time,false,fb);
    if(fa||fb||abs(flatA-flatB)>.00005)failures++;
    // The positive gate requires real wave displacement on BOTH sides, not a globally flat workaround.
    float error=EXPECT_CONTINUOUS!=0?max(abs((flatA-ta)-expected),abs((flatB-tb)-expected)):abs(gap-expected);
    if(EXPECT_CONTINUOUS!=0) {if(gap>.00005||error>.00005)failures++;}
    else {if(gap<.001||error>.00005)failures++;}
    worldPush.waterFieldAddr=0;
    float wetA=root(a,ro,time,true,fa),wetB=root(b,ro,time,true,fb);
    if(fa||fb||abs(wetA-wetB)>.00005||abs((flatA-wetA)-expected)>.00005)failures++;
    layerResults[c]=float4(float(failures),gap,error,exp2(float((c/2)%5)));
}
