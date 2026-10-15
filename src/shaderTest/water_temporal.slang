// Compare production temporal guides against the actual field at the previous frame's phase.
import water;
import world_core;
import water_test_frame;
RWStructuredBuffer<uint> result;

[shader("compute")]
[numthreads(1,1,1)]
void waterTemporalProbe(uint3 id : SV_DispatchThreadID) {
    uint failures=0,cases=0,currentFailures=0;
    float maxHeight=0.0,maxNormal=0.0;
    float heightByDelta[6]={0,0,0,0,0,0};
    float normalByDelta[6]={0,0,0,0,0,0};
    uint failuresByDelta[6]={0,0,0,0,0,0};
    float times[5]={0.0,0.37,17.51,1023.875,3599.5};
    float deltas[6]={0.0,1.0/240.0,1.0/60.0,1.0/30.0,0.1,0.25};
    setWaterTestDomain(float2(0.0));
    for(int ti=0;ti<5;ti++) for(int sample=0;sample<16;sample++)
    for(int si=0;si<3;si++) for(int speedIndex=0;speedIndex<3;speedIndex++)
    for(int di=0;di<6;di++) for(int fi=0;fi<3;fi++) {
        float strength=si==0?0.0:si==1?1.0:4.0;
        float speed=speedIndex==0?0.0:speedIndex==1?1.0:4.0;
        float footprint=fi==0?0.0:fi==1?2.0:16.0;
        worldPush.waterOpacity=float4(1.0,strength,speed,7.0);
        float2 p=float2(float(sample*17-113),float(sample*11-79));
        float t=times[ti],prevT=t-deltas[di];
        float height,previousHeight,exactHeight,unusedDt,currentHeight;
        float2 grad,previousGrad,exactGrad,unusedGradDt,currentGrad;
        waterWaveFieldTemporal(p,t,prevT,footprint,height,grad,previousHeight,previousGrad);
        waterWaveSpectrum<0>(p,prevT,footprint,exactHeight,exactGrad,unusedDt,unusedGradDt);
        waterWaveSpectrum<0>(p,t,footprint,currentHeight,currentGrad,unusedDt,unusedGradDt);
        if(abs(height-currentHeight)>1.0e-6 || length(grad-currentGrad)>1.0e-6) currentFailures++;
        float3 baseNormal=normalize(float3(-0.4,1.0,0.3));
        float heightError=abs(previousHeight-exactHeight);
        float normalError=length(waterSurfaceNormal(baseNormal,previousGrad)-waterSurfaceNormal(baseNormal,exactGrad));
        maxHeight=max(maxHeight,heightError); maxNormal=max(maxNormal,normalError);
        heightByDelta[di]=max(heightByDelta[di],heightError);
        normalByDelta[di]=max(normalByDelta[di],normalError);
        // 0.1 mm in the vertical motion guide and ~0.057 degrees in the normal guide.
        if(heightError>1.0e-4 || normalError>0.001 || !isfinite(heightError) || !isfinite(normalError)) {
            failures++; failuresByDelta[di]++;
        }
        cases++;
    }
    result[0]=failures+currentFailures; result[1]=cases; result[2]=currentFailures;
    result[3]=asuint(maxHeight); result[4]=asuint(maxNormal);
    for(int di=0;di<6;di++) {
        result[5+di*3]=failuresByDelta[di];
        result[6+di*3]=asuint(heightByDelta[di]);
        result[7+di*3]=asuint(normalByDelta[di]);
    }
}
