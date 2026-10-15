// Execute production wave evaluation; finite differences and the zero-amplitude identity are oracles.
import water;
import world_core;
RWStructuredBuffer<uint> result;

[shader("compute")]
[numthreads(1,1,1)]
void waterSurfaceProbe(uint3 id : SV_DispatchThreadID) {
    uint zeroFailures=0, normalFailures=0, heightFailures=0, anchorFailures=0, cases=0;
    float worstNormal=0.0, worstHeight=0.0;
    // exp(S*(sin(phi)-1)) and each LOD weight are <= 1: this is an analytic envelope,
    // independent of the sampled phases below. The height slider scales both sides equally.
    float envelope=0.0,lambda=WAVE_LAMBDA0;
    for(int i=0;i<WAVE_COUNT;i++) {
        envelope+=WAVE_Q[i]*lambda/6.28318530718;
        lambda/=WAVE_LAMBDA_RATIO;
    }
    if(envelope*WATER_WAVE_STRENGTH>WAVE_MAX_HEIGHT) heightFailures++;
    worldPush.waterAnchor=float4(0.0);
    worldPush.waterOpacity=float4(1.0,0.0,1.0,7.0);
    for(int side=0;side<2;side++) for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) {
        float sign=side==0?1.0:-1.0;
        float3 n=sign*normalize(float3(float(x)/5.0,1.0,float(z)/5.0));
        float3 rd=float3(0.0,-sign,0.0),p=float3(2.0,112.0,-3.0);
        WaterWaves w=evaluateWaterWaves(p-rd*4.0,rd,4.0,n,1.0,0.99,0.0);
        float error=length(w.normal-n);
        if(error>1.0e-5 || length(w.previousNormal-n)>1.0e-5
                || length(w.position-p)>1.0e-5 || length(applyWaterWaves(n,p.xz,1.0,0.0)-n)>1.0e-5) zeroFailures++;
        worstNormal=max(worstNormal,error); cases++;
    }
    // Vertical rays have a unique known height; oblique rays also test the sloped-plane residual.
    for(int strengthIndex=0;strengthIndex<3;strengthIndex++) for(int timeIndex=0;timeIndex<8;timeIndex++)
    for(int sample=0;sample<16;sample++) for(int side=0;side<2;side++)
    for(int ray=0;ray<2;ray++) for(int footprintIndex=0;footprintIndex<4;footprintIndex++) {
        float strength=strengthIndex==0?0.25:strengthIndex==1?1.0:4.0;
        worldPush.waterOpacity.y=strength;
        float sign=side==0?1.0:-1.0;
        float3 n=sign*normalize(float3(-0.4,1.0,0.3));
        float3 rd=sign*(ray==0?float3(0.0,-1.0,0.0):normalize(float3(-0.3,-1.0,0.2)));
        float3 p=float3(float(sample*7-51),112.0,float(sample*11-79));
        float t=float(timeIndex)*1.37;
        float footprint=footprintIndex==0?0.0:footprintIndex==1?0.5:footprintIndex==2?2.0:16.0;
        WaterWaves w=evaluateWaterWaves(p-rd*8.0,rd,8.0,n,t,t-0.016,footprint);
        float2 delta=w.position.xz-p.xz;
        float expectedY=p.y-dot(n.xz,delta)/n.y+waterWaveHeight(w.position.xz,t,footprint);
        float heightError=abs(w.position.y-expectedY);
        worstHeight=max(worstHeight,heightError);
        if(heightError>(ray==0?3.0e-5:0.001) || any(!isfinite(w.position))) heightFailures++;
        // Differentiate the complete sloped height field, using local heights to avoid cancellation at Y=112.
        const float e=0.002;
        float dx=(waterWaveHeight(w.position.xz+float2(e,0.0),t,footprint)-waterWaveHeight(w.position.xz-float2(e,0.0),t,footprint))/(2.0*e);
        float dz=(waterWaveHeight(w.position.xz+float2(0.0,e),t,footprint)-waterWaveHeight(w.position.xz-float2(0.0,e),t,footprint))/(2.0*e);
        float3 expectedNormal=sign*normalize(float3(-(0.4+dx),1.0,-(-0.3+dz)));
        float normalError=length(w.normal-expectedNormal);
        worstNormal=max(worstNormal,normalError);
        if(normalError>0.003 || any(!isfinite(w.normal))) normalFailures++;
        cases++;
    }
    // Both representations describe the same world point and wave phase across a camera rebase.
    worldPush.waterOpacity=float4(1.0,1.0,1.0,7.0);
    for(int step=0;step<32;step++) {
        float3 p=float3(2.0,112.0,-3.0),rd=float3(0.0,-1.0,0.0),n=normalize(float3(-0.4,1.0,0.3));
        worldPush.waterAnchor=float4(2048.0,-4096.0,0.0,0.0);
        WaterWaves a=evaluateWaterWaves(p-rd*4.0,rd,4.0,n,float(step)*0.07,float(step)*0.07-0.016,0.0);
        float3 shift=float3(512.0,0.0,-1024.0);
        worldPush.waterAnchor.xy+=shift.xz;
        WaterWaves b=evaluateWaterWaves(p-shift-rd*4.0,rd,4.0,n,float(step)*0.07,float(step)*0.07-0.016,0.0);
        if(length(a.position-(b.position+shift))>1.0e-4 || length(a.normal-b.normal)>1.0e-4
                || length(a.previousNormal-b.previousNormal)>1.0e-4 || length(a.motionPrev-b.motionPrev)>1.0e-4) anchorFailures++;
        cases++;
    }
    result[0]=zeroFailures+normalFailures+heightFailures+anchorFailures;
    result[1]=cases; result[2]=zeroFailures; result[3]=normalFailures;
    result[4]=heightFailures; result[5]=anchorFailures;
    result[6]=asuint(worstNormal); result[7]=asuint(worstHeight);
}
