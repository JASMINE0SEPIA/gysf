import water;
import water_patch;
import water_patch_cases;
import water_test_frame;
import world_core;
RWStructuredBuffer<uint> result;
RWStructuredBuffer<float4> rayResults;

bool intervalMatches(WaterPatch patch,float3 ro,float3 rd,float lower,float upper,
        float rayMin,float rayMax,bool expected,float expectedLo,float expectedHi) {
    float lo=rayMin,hi=rayMax;
    bool actual=patchInterval(patch,ro,rd,lower,upper,lo,hi);
    return actual==expected && (!expected || (abs(lo-expectedLo)<1e-5 && abs(hi-expectedHi)<1e-5));
}

uint analyticClipChecks(out uint count) {
    WaterPatch p;p.point=float3(0);p.normal=float3(0,1,0);p.minimum=float2(0);p.extent=float2(2,3);
    p.shore=float4(1);p.half=0;
    uint failures=0;count=0;
    count++;if(!intervalMatches(p,float3(-1,.5,.75),float3(1,0,0),0,1,0,100,true,1.5,3))failures++;
    p.half=1;
    count++;if(!intervalMatches(p,float3(-1,.5,.75),float3(1,0,0),0,1,0,100,true,1,1.5))failures++;
    p.half=0;
    count++;if(!intervalMatches(p,float3(.5,.5,-1),float3(0,0,1),0,1,0,100,true,1,1.75))failures++;
    p.half=1;
    count++;if(!intervalMatches(p,float3(.5,.5,-1),float3(0,0,1),0,1,0,100,true,1.75,4))failures++;
    p.half=0;
    count++;if(!intervalMatches(p,float3(-1,2,.75),float3(1,0,0),0,1,0,100,false,0,0))failures++;
    count++;if(!intervalMatches(p,float3(.5,2,.3),float3(0,-1,0),.2,.8,0,100,true,1.2,1.8))failures++;
    count++;if(!intervalMatches(p,float3(.5,-2,.3),float3(0,1,0),.2,.8,0,100,true,2.2,2.8))failures++;
    count++;if(!intervalMatches(p,float3(.5,2,.3),float3(0,-1,0),.2,.8,1.4,1.6,true,1.4,1.6))failures++;
    count++;if(!intervalMatches(p,float3(.5,2,.3),float3(0,-1,0),.2,.8,2,3,false,0,0))failures++;
    count++;if(!intervalMatches(p,float3(.5,2,.3),float3(0,-1,0),0,0,0,100,true,2,2))failures++;
    count++;if(!intervalMatches(p,float3(-1,0,.75),float3(1,0,0),0,0,0,100,false,0,0))failures++;
    count++;if(!intervalMatches(p,float3(-1,.5,0),float3(1,0,0),0,1,0,100,true,1,3))failures++;
    p.normal=normalize(float3(-.5,1,0));
    count++;if(!intervalMatches(p,float3(1,2,.3),float3(0,-1,0),.2,.8,0,100,true,.7,1.3))failures++;
    // Tiny nonzero rates are not parallel. A fixed epsilon discards real crossings on long rays.
    float lo=0,hi=2000;
    count++;if(!clipPatchLinear(-1e-6,1e-9,lo,hi)||abs(lo-1000)>.001||hi!=2000)failures++;
    lo=0;hi=2000;
    count++;if(!clipPatchLinear(1e-6,-1e-9,lo,hi)||lo!=0||abs(hi-1000)>.001)failures++;
    lo=0;hi=10;
    count++;if(clipPatchLinear(-1,0,lo,hi))failures++;
    lo=0;hi=10;
    count++;if(!clipPatchLinear(0,0,lo,hi)||lo!=0||hi!=10)failures++;
    return failures;
}

float referencePatch(WaterPatch patch,float3 ro,float3 rd,float time,float footprint,float step) {
    float lower,upper,curvature;patchBounds(patch,rd.xz,footprint,lower,upper,curvature);
    float lo=0.0001,hi=100.0;if(!patchInterval(patch,ro,rd,lower,upper,lo,hi))return -1.0;
    float dummy,fa=patchEquation(patch,ro,rd,lo,time,footprint,dummy),a=lo;
    if(abs(fa)<1e-7)return a;
    for(int i=0;i<65536;i++) {
        float b=min(a+step,hi),fb=patchEquation(patch,ro,rd,b,time,footprint,dummy);
        if(abs(fb)<1e-7 || fa*fb<=0) {
            for(int j=0;j<20;j++) {
                float mid=(a+b)*0.5,fm=patchEquation(patch,ro,rd,mid,time,footprint,dummy);
                if(fa*fm>0){a=mid;fa=fm;}else b=mid;
            }
            return (a+b)*0.5;
        }
        if(b==hi||b==a)break;a=b;fa=fb;
    }
    return -1.0;
}

[shader("compute")]
[numthreads(1,1,1)]
void patchProbe(uint3 id:SV_DispatchThreadID) {
    uint failures=0,hits=0,misses=0,disagreements=0,exhaustions=0,maxWork=0,totalWork=0,properties=0;
    float maxResidual=0.0,maxDistance=0.0;
    uint gradientFailures=0,boundsFailures=0,curvatureFailures=0;
    float maxGradientError=0.0,maxCurvatureFraction=0.0;
    setWaterTestDomain(float2(0.0));
    for(uint c=0;c<4096;c++) {
        WaterPatch patch;float3 ro,rd;float time,footprint;
        waterPatchCase(c,patch,ro,rd,time,footprint);
        uint work=0;bool exhausted=false,uncertain=false;float t=-1,b=-1;WaterPatch selectedPatch=patch;
        for(uint candidate=0;candidate<(GYSF_PATCH_SCENE?8u:1u);candidate++) {
            WaterPatch shape=GYSF_PATCH_SCENE?waterPatchGeometry(candidate/2u,candidate&1u):patch;
            uint steps;bool usedUp;
            float hit=intersectWaterPatch(shape,ro,rd,time,footprint,0.0001,100.0,steps,usedUp);
            work+=steps;exhausted=exhausted||usedUp;
            float first=referencePatch(shape,ro,rd,time,footprint,0.005),second=referencePatch(shape,ro,rd,time,footprint,0.0025);
            uncertain=uncertain||((first<0)!=(second<0)||(first>=0&&abs(first-second)>0.001));
            if(hit>=0&&(t<0||hit<t)){t=hit;selectedPatch=shape;}
            if(second>=0&&(b<0||second<b))b=second;
        }
        if(uncertain)disagreements++;
        bool mismatch=(t<0)!=(b<0);float distance=t>=0&&b>=0?abs(t-b):0.0;
        float residual=0.0;
        if(t>=0) {
            hits++;float dummy;residual=abs(patchEquation(selectedPatch,ro,rd,t,time,footprint,dummy));
            float2 uv=((ro+rd*t).xz-selectedPatch.minimum)/selectedPatch.extent;
            if(any(uv< -1e-4)||any(uv>1.0001)||(selectedPatch.half==0?uv.x+1e-4<uv.y:uv.y+1e-4<uv.x))mismatch=true;
        }else misses++;
        if(mismatch||distance>0.02||residual>0.001||!isfinite(residual))failures++;
        if(exhausted)exhaustions++;
        maxResidual=max(maxResidual,residual);maxDistance=max(maxDistance,distance);maxWork=max(maxWork,work);totalWork+=work;
        rayResults[c]=float4(t,b,distance,uncertain?1.0:0.0);
        // Independent finite differences include the shoreline envelope's product-rule term.
        float2 p=patch.minimum+float2(.125+float((c*17u)%13u)/16.0,.125+float((c*29u)%13u)/16.0);
        float h,hxp,hxm,hzp,hzm;float2 gradient,unused;
        patchField(patch,p,time,footprint,h,gradient);
        patchField(patch,p+float2(.001,0),time,footprint,hxp,unused);
        patchField(patch,p-float2(.001,0),time,footprint,hxm,unused);
        patchField(patch,p+float2(0,.001),time,footprint,hzp,unused);
        patchField(patch,p-float2(0,.001),time,footprint,hzm,unused);
        float gradientError=length(gradient-float2(hxp-hxm,hzp-hzm)/.002);
        maxGradientError=max(maxGradientError,gradientError);
        if(!isfinite(gradientError)||gradientError>.001)gradientFailures++;
        float2 direction=normalize(float2(.37,1));float lower,upper,curvature;float2 gp,gm;
        patchBounds(patch,direction,footprint,lower,upper,curvature);
        if(h<lower-1e-5||h>upper+1e-5)boundsFailures++;
        patchField(patch,p+direction*.002,time,footprint,hxp,gp);
        patchField(patch,p-direction*.002,time,footprint,hxm,gm);
        float measuredBend=abs(dot(gp-gm,direction)/.004);
        maxCurvatureFraction=max(maxCurvatureFraction,measuredBend/curvature);
        if(!isfinite(measuredBend)||measuredBend>curvature+.001)curvatureFailures++;
    }
    // Shared-edge value/gradient continuity and dry-edge flattening, with an active wave field.
    worldPush.waterOpacity=float4(1,1,1,7);
    for(int i=0;i<=16;i++) {
        WaterPatch a,b;a.point=b.point=float3(0);a.normal=b.normal=float3(0,1,0);a.extent=b.extent=float2(1);
        a.minimum=float2(0,0);b.minimum=float2(1,0);a.shore=float4(0,0,1,0);b.shore=float4(1,0,1,1);
        float2 p=float2(1.0,float(i)/16.0),ga,gb;float ha,hb;
        patchField(a,p,0.37,0,ha,ga);patchField(b,p,0.37,0,hb,gb);
        if(abs(ha-hb)>1e-6||length(ga-gb)>1e-6)properties++;
        patchField(a,float2(0,p.y),0.37,0,ha,ga);
        if(abs(ha)>1e-6||length(ga)>1e-6)properties++;
        a=waterPatchGeometry(2,0);b=waterPatchGeometry(2,1);
        p=a.minimum+float2(float(i)/16.0);
        float ya=a.point.y-dot(a.normal.xz,p-a.point.xz)/a.normal.y;
        float yb=b.point.y-dot(b.normal.xz,p-b.point.xz)/b.normal.y;
        if(abs(ya-yb)>1e-5)properties++;
    }
    result[0]=failures;result[1]=4096;result[2]=hits;result[3]=misses;result[4]=disagreements;
    result[5]=exhaustions;result[6]=maxWork;result[7]=totalWork;result[8]=asuint(maxResidual);result[9]=asuint(maxDistance);result[10]=properties;
    uint clipCount;result[11]=analyticClipChecks(clipCount);result[12]=clipCount;
    result[13]=gradientFailures;result[14]=asuint(maxGradientError);result[15]=boundsFailures;
    result[16]=curvatureFailures;result[17]=asuint(maxCurvatureFraction);
}
