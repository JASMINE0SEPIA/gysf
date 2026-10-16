import world_common;
import world_core;
import water;
import water_primitive;
import water_field;
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

// Independent fixture oracle: actual known water is at layer 175; higher layers are absent.
float fixtureWeight(float2 point,float size,float low,float high,float extra,int anchorY,int wetLayer) {
    int2 cell=int2(floor(point));float4 weights;
    for(int i=0;i<4;i++) {
        float2 node=float2(cell+int2(i>>1,i&1));
        float u=clamp((node.x-32)/size,0.0,1.0),v=clamp((node.y-16)/size,0.0,1.0);
        float y=(1-u)*low+u*((1-v)*high+v*(high+extra));
        int layer=int(ceil(y-.001))-1+anchorY;
        weights[i]=layer==wetLayer?1.0:0.0;
    }
    float2 f=point-float2(cell),s=f*f*(3-2*f);
    return (1-s.x)*((1-s.y)*weights.x+s.y*weights.y)+s.x*((1-s.y)*weights.z+s.y*weights.w);
}

[shader("compute")]
[numthreads(64,1,1)]
void fieldProbe(uint3 id:SV_DispatchThreadID) {
    uint c=id.x;if(c>=4096)return;
    bool fullCell=(c&8)!=0;
    int wetLayer=fullCell?176:175;
    worldPush.waterFieldAddr=layerAddresses[fullCell?3:0];worldPush.waterPreviousFieldAddr=layerAddresses[1];
    worldPush.waterOpacity=float4(0,(c&1)==0?1:4,1,7);
    int anchorY=(int((c/90)%3)-1)*128;
    worldPush.sharcGridOrigin=int4(0,anchorY,0,0);setWaterTestDomain(float2(0));
    WaterPrimitive a=primitive(c*3),b=primitive(c*3+1),other=primitive(c*3+2);
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
    if(EXPECT_CONTINUOUS!=0) {
        // The higher known/unknown side must not acquire waves merely to close the low edge.
        float3 highRay=float3(b.v2.x,180-anchorY,b.v2.z);
        float highHit=root(b,highRay,time,true,fa),highFlat=root(b,highRay,time,false,fb);
        if(fa||fb||abs(highHit-highFlat)>.00005)failures++;
        float size=exp2(float((c/2)%5)),q=5.0/32.0;
        float2 diagonal=float2(32+q*size,16+((c&2)==0?q:1-q)*size);
        float3 diagonalRay=float3(diagonal.x,180-anchorY,diagonal.y);
        float d0=root(b,diagonalRay,time,true,fa),d1=root(other,diagonalRay,time,true,fb);
        if(fa||fb)failures++;
        float f0=root(b,diagonalRay,time,false,fa),f1=root(other,diagonalRay,time,false,fb);
        float low=(fullCell?176.0+round(65535.0/16.0)/65535.0*16.0:175.0+8.0/9.0)-anchorY;
        float high=(fullCell?177.25:176.25)-anchorY;
        float expectedD=waterWaveHeight(diagonal,time,0)*fixtureWeight(diagonal,size,low,high,(c&4)!=0?high-low:0,anchorY,wetLayer);
        gap=max(gap,abs(d0-d1));
        error=max(error,max(abs((f0-d0)-expectedD),abs((f1-d1)-expectedD)));
        if(fa||fb||abs(f0-f1)>.00005||gap>.00005||error>.00005)failures++;
        WaterWaves w0=primitiveWaterWaves(b,float3(0),diagonalRay+float3(0,-d0,0),d0,true,time,time);
        WaterWaves w1=primitiveWaterWaves(other,float3(0),diagonalRay+float3(0,-d1,0),d1,true,time,time);
        if(abs(w0.motionPrev.y-expectedD)>.00005||abs(w1.motionPrev.y-expectedD)>.00005)failures++;
        float3 restNormal=normalize(cross(b.v1.xyz-b.v0.xyz,b.v2.xyz-b.v0.xyz));
        if(length(w0.previousNormal-restNormal)>.000005)failures++;
        worldPush.waterFieldAddr=layerAddresses[1];
        if((waterNodeWord(worldPush.waterFieldAddr,int2(32,16),wetLayer)&65536u)==0)failures++;
        float zeroA=root(a,ro,time,true,fa),zeroB=root(b,ro,time,true,fb);
        if(fa||fb||abs(zeroA-flatA)>.00005||abs(zeroB-flatB)>.00005)failures++;
    }
    worldPush.waterFieldAddr=0;
    float wetA=root(a,ro,time,true,fa),wetB=root(b,ro,time,true,fb);
    if(fa||fb||abs(wetA-wetB)>.00005||abs((flatA-wetA)-expected)>.00005)failures++;
    layerResults[c]=float4(float(failures),gap,error,exp2(float((c/2)%5)));
}
