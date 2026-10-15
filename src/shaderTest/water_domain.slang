import water;
import world_core;
StructuredBuffer<float4> input;
RWStructuredBuffer<float4> result;

[shader("compute")]
[numthreads(1,1,1)]
void waterDomainProbe(uint3 id : SV_DispatchThreadID) {
    worldPush.waterOpacity=input[0];
    worldPush.waterAnchor=float4(0.0);
    float2 p=input[1].xy;
    float t=input[1].z, footprint=input[1].w;
    float basisError=0.0;
    for(int i=0;i<WAVE_COUNT;i++) {
        worldPush.waterWaveDomains[i].axes=input[3+i*2];
#if WATER_DOMAIN_PHASED
        worldPush.waterWaveDomains[i].phase=input[4+i*2];
#else
        worldPush.waterWaveDomains[i].phase=float4(0.0,0.0,input[4+i*2].z,0.0);
#endif
        basisError=max(basisError,max(max(abs(input[3+i*2].x-waterWaveBasis(i).x),abs(input[3+i*2].y-waterWaveBasis(i).y)),
                max(abs(input[3+i*2].z-waterWaveBasis(i).z),abs(input[3+i*2].w-waterWaveBasis(i).w))));
        basisError=max(basisError,abs(input[4+i*2].z-waterWaveFrequency(i)));
    }
#if !WATER_DOMAIN_PHASED
    // Exact pre-fix CPU policy, retained only as the negative control.
    worldPush.waterAnchor.xy=float2(int(input[2].x)&4095,int(input[2].y)&4095);
    t=input[2].z;
#endif
    float height,previousHeight;
    float2 grad,previousGrad;
    waterWaveFieldTemporal(p+worldPush.waterAnchor.xy,t,t-0.016,footprint,height,grad,previousHeight,previousGrad);
    result[0]=float4(height,grad,previousHeight);
    result[1]=float4(previousGrad,basisError,0.0);
}
