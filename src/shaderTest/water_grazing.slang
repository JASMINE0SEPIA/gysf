import water;
import water_grazing_solver;
import water_grazing_reference;
import water_test_frame;
import world_core;
RWStructuredBuffer<uint> result;
[shader("compute")]
[numthreads(1,1,1)]
void grazingProbe(uint3 id:SV_DispatchThreadID) {
    uint failures=0,cases=0,exhaustions=0,maxWork=0,totalWork=0;
    uint references=0,referenceFailures=0,referenceDisagreements=0;
    float worstRootError=0.0;
    float worst=0.0,worstAngle=0.0,worstStrength=0.0,worstTime=0.0;
    float angles[8]={0.25,0.5,1.0,2.0,4.0,8.0,15.0,30.0};
    setWaterTestDomain(float2(0.0));
    for(int si=0;si<2;si++) for(int ai=0;ai<8;ai++) for(int ti=0;ti<8;ti++)
    for(int sample=0;sample<4;sample++) for(int side=0;side<2;side++) for(int fi=0;fi<4;fi++) {
        float strength=si==0?1.0:4.0;
        worldPush.waterOpacity=float4(1.0,strength,1.0,7.0);
        float theta=angles[ai]*PI/180.0;
        float sign=side==0?1.0:-1.0;
        float3 rd=float3(cos(theta),-sign*sin(theta),0.0),n=float3(0.0,sign,0.0);
        float3 plane=float3(float(sample*11-17),112.0,float(sample*7-9));
        float hitT=(side==0?WAVE_MAX_HEIGHT*strength+0.5:0.5)/sin(theta);
        float3 ro=plane-rd*hitT;
        float time=float(ti)*1.37,footprint=fi==0?0.0:fi==1?0.5:fi==2?2.0:8.0;
#if GYSF_GRAZING_PROTOTYPE
        uint work; bool exhausted;
        float rootT=strictWaveHit(ro,rd,hitT,n,time,footprint,work,exhausted);
        WaterWaves w=flatWaterWaves(ro+rd*rootT,rootT,n);
        exhaustions+=exhausted?1:0; maxWork=max(maxWork,work);totalWork+=work;
        if(sample==0 && fi==0 && ti==0) {
            float3 actualPlane=ro+rd*hitT;
            float referenceA=denseWaterHit(actualPlane,rd,hitT,time,strength,0.01);
            float referenceB=denseWaterHit(actualPlane,rd,hitT,time,strength,0.005);
            float error=abs(rootT-referenceB);
            if(referenceA<0 || referenceB<0 || abs(referenceA-referenceB)>0.001) referenceDisagreements++;
            if(error>0.02) referenceFailures++;
            worstRootError=max(worstRootError,error);references++;
        }
#else
        WaterWaves w=evaluateWaterWaves(ro,rd,hitT,n,time,time-0.016,footprint);
#endif
        float residual=abs(w.position.y-plane.y-waterWaveHeight(w.position.xz,time,footprint));
        if(residual>worst) {worst=residual;worstAngle=angles[ai];worstStrength=strength;worstTime=time;}
        if(!isfinite(residual)||residual>0.001) failures++;
        cases++;
    }
    result[0]=failures;result[1]=cases;result[2]=asuint(worst);result[3]=asuint(worstAngle);
    result[4]=asuint(worstStrength);result[5]=asuint(worstTime);result[6]=maxWork;result[7]=totalWork;result[8]=exhaustions;
    result[9]=references;result[10]=referenceFailures;result[11]=asuint(worstRootError);result[12]=referenceDisagreements;
}

