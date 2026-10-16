import water;
import water_patch;
import water_patch_cases;
import water_primitive;
import water_test_frame;
import world_core;
import math;
import reflection_motion;
RWStructuredBuffer<uint> result;
RWStructuredBuffer<float4> rayResults;
RWStructuredBuffer<float4> packedPrimitives;

WaterPatch probeGeometry(uint sample,uint half) {
    uint count,stride;packedPrimitives.GetDimensions(count,stride);
    if(count==40) {
        uint base=(sample*2u+half)*5u;
        WaterPrimitive primitive;
        primitive.v0=packedPrimitives[base];primitive.v1=packedPrimitives[base+1];primitive.v2=packedPrimitives[base+2];
        primitive.rectangle=packedPrimitives[base+3];primitive.shore=packedPrimitives[base+4];
        return primitiveWaterPatch(primitive,float3(0));
    }
    return waterPatchGeometry(sample,half);
}

bool primitiveMatches(WaterPrimitive primitive,float3 ro,float3 rd,bool animated,float lo,float hi,
        float expected,float2 expectedBary) {
    float2 bary;uint work;bool exhausted;
    float t=intersectWaterPrimitive(primitive,float3(0),ro,rd,.37,0,animated,lo,hi,bary,work,exhausted);
    return !exhausted && (expected<0?t<0:abs(t-expected)<1e-5&&length(bary-expectedBary)<1e-5);
}

uint flatPrimitiveChecks(out uint count) {
    WaterPrimitive p;p.v0=float4(0,0,0,0);p.v1=float4(0,0,1,1);p.v2=float4(1,0,1,0);
    p.rectangle=float4(0,0,1,1);p.shore=float4(1);
    uint failures=0;count=0;float2 bary=float2(.5,.25);
    count++;if(!primitiveMatches(p,float3(.25,2,.75),float3(0,-1,0),true,0,100,2,bary))failures++;
    count++;if(!primitiveMatches(p,float3(.25,-2,.75),float3(0,1,0),true,0,100,2,bary))failures++;
    count++;if(!primitiveMatches(p,float3(.75,2,.25),float3(0,-1,0),true,0,100,-1,0))failures++;
    count++;if(!primitiveMatches(p,float3(.25,2,.75),float3(0,-1,0),true,2.1,100,-1,0))failures++;
    count++;if(!primitiveMatches(p,float3(.25,2,.75),float3(0,-1,0),true,0,1.9,-1,0))failures++;
    count++;if(!primitiveMatches(p,float3(.25,2,.75),float3(1,0,0),true,0,100,-1,0))failures++;
    p.v0.w=1;
    count++;if(!primitiveMatches(p,float3(.25,2,.75),float3(0,-1,0),false,0,100,2,bary))failures++;
    p.shore=0;
    count++;if(!primitiveMatches(p,float3(.25,2,.75),float3(0,-1,0),true,0,100,2,bary))failures++;
    p.v0.w=0;p.v1=float4(0,0,1,0);p.v2=float4(0,1,1,0);
    count++;if(!primitiveMatches(p,float3(2,.25,.75),float3(-1,0,0),true,0,100,2,bary))failures++;
    count++;if(!primitiveMatches(p,float3(-2,.25,.75),float3(1,0,0),true,0,100,2,bary))failures++;
    return failures;
}

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
    p.half=2;
    count++;if(!intervalMatches(p,float3(-1,.5,.75),float3(1,0,0),0,1,0,100,true,1,2.5))failures++;
    count++;if(!intervalMatches(p,float3(.5,.5,-1),float3(0,0,1),0,1,0,100,true,1,3.25))failures++;
    p.half=3;
    count++;if(!intervalMatches(p,float3(-1,.5,.75),float3(1,0,0),0,1,0,100,true,2.5,3))failures++;
    count++;if(!intervalMatches(p,float3(.5,.5,-1),float3(0,0,1),0,1,0,100,true,3.25,4))failures++;
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

uint reflectionMotionChecks(out uint count) {
    float4x4 savedCurrent=worldPush.curViewProj,savedPrevious=worldPush.prevViewProj;
    float3 savedOffset=worldPush.camOffset,savedDelta=worldPush.camDelta;
    worldPush.curViewProj=float4x4(1.3,0,0,0, 0,1.7,0,0, 0,0,-1,-.1, 0,0,-1,0);
    worldPush.prevViewProj=worldPush.curViewProj;worldPush.camOffset=float3(0);worldPush.camDelta=float3(0);
    uint failures=0;count=0;
    for(int i=0;i<32;i++) {
        float3 surface=float3(.3,-2,-5),normal=normalize(float3(.01*float(i),1,.003*float(i)));
        float3 direction=normalize(float3(.003*float(i),.02+.003*float(i),-1));
        // Include a biased ray origin, grazing rays and finite/sky endpoints.
        float3 hit=surface+normal*.01+reflect(direction,normal)*(i%2==0?float(i+1):1e6);
        bool valid;
        float2 motion=reflectionMotionNdc(surface,normal,normal,float3(0),hit,float3(0),valid);
        count++;if(!valid||any(motion!=float2(0)))failures++;
        // Independent horizontal-plane mirror and perspective projection, with moving content,
        // moving surface and translated camera. Tangential surface motion must cancel.
        hit=float3(.2*float(i),3,-17-float(i));normal=float3(0,1,0);
        float3 surfaceMotion=float3(.03,.05,-.02),hitMotion=float3(.1,-.2,.3),delta=float3(-.3,.2,.1);
        worldPush.camDelta=delta;
        float2 current=float2(1.3*hit.x,1.7*(2*surface.y-hit.y))/-hit.z;
        float3 oldHit=hit-hitMotion+delta,oldSurface=surface-surfaceMotion+delta;
        float2 previous=float2(1.3*oldHit.x,1.7*(2*oldSurface.y-oldHit.y))/-oldHit.z;
        motion=reflectionMotionNdc(surface,normal,normal,surfaceMotion,hit,hitMotion,valid);
        count++;if(!valid||length(motion-(previous-current))>1e-6)failures++;
        worldPush.camDelta=float3(0);
    }
    bool valid;
    float2 behind=reflectionMotionNdc(float3(0),float3(0,1,0),float3(0,1,0),float3(0),float3(0,0,2),float3(0),valid);
    count++;if(valid||any(behind!=float2(0)))failures++;
    // Normal rotation changes a real reflected feature even with no positional motion.
    float2 rotated=reflectionMotionNdc(float3(0),float3(0,1,0),float3(1,0,0),float3(0),float3(2,3,-10),float3(0),valid);
    count++;if(!valid||length(rotated-float2(-.52,1.02))>1e-6)failures++;
    worldPush.curViewProj=savedCurrent;worldPush.prevViewProj=savedPrevious;
    worldPush.camOffset=savedOffset;worldPush.camDelta=savedDelta;
    return failures;
}

uint runtimePrimitiveChecks(out uint count,out float maxNormalError) {
    uint failures=0;count=0;maxNormalError=0;
    float4x4 savedProjection=worldPush.invViewProj;float3 savedCamera=worldPush.camOffset;
    // Execute the actual shared camera helper with perspective/bob/rotation/zoom matrices. The
    // independent expected centre is the translated camera-to-world origin, not a near-plane point.
    for(int i=0;i<8;i++) {
        float angle=float(i)*.37,c=cos(angle),s=sin(angle);
        float3 translation=float3(float(i)*.17-.4,float(i)*-.08+.3,float(i)*.12-.2);
        float4x4 cameraToWorld=float4x4(c,0,s,translation.x, 0,1,0,translation.y,
                -s,0,c,translation.z, 0,0,0,1);
        float4x4 inverseProjection=float4x4(.3+float(i)*.13,0,0,0, 0,.4+float(i)*.11,0,0,
                0,0,0,-1, 0,0,19.9999,.0001);
        worldPush.invViewProj=mul(cameraToWorld,inverseProjection);worldPush.camOffset=float3(13,-7,21);
        float3 eye;bool valid=cameraProjectionEye(eye);
        float4 nearH=mul(worldPush.invViewProj,float4(.31,-.42,1,1));
        float4 farH=mul(worldPush.invViewProj,float4(.31,-.42,0,1));
        count++;
        if(!valid || length(eye-(translation+worldPush.camOffset))>1e-5
                || dot(normalize(nearH.xyz/nearH.w+worldPush.camOffset-eye),
                       normalize(farH.xyz/farH.w+worldPush.camOffset-eye))<.999999)failures++;
    }
    worldPush.invViewProj=float4x4(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1);
    float3 orthoEye;count++;if(cameraProjectionEye(orthoEye)||any(orthoEye!=worldPush.camOffset))failures++;
    worldPush.invViewProj=savedProjection;worldPush.camOffset=savedCamera;
    float4 clips[7]={float4(0,0,0,1),float4(0,0,1,1),float4(0,0,2,1),float4(0,0,-.1,1),
            float4(0,0,.25,1),float4(0,0,.5,0),float4(0,0,.5,-1)};
    float depths[7]={0,1,1,0,.25,0,0};
    for(int i=0;i<7;i++) {count++;if(abs(guideDeviceDepth(clips[i])-depths[i])>1e-7)failures++;}
    // Full-sphere normals cover both viewed sides, poles, axes and the octahedral seam.
    for(int i=0;i<=32;i++)for(int j=0;j<64;j++) {
        float y=-1.0+2.0*float(i)/32.0,angle=float(j)*6.28318530718/64.0;
        float radius=sqrt(max(0.0,1.0-y*y));
        float3 normal=float3(radius*cos(angle),y,radius*sin(angle));
        float error=length(normal-unpackWaterGuideNormal(packWaterGuideNormal(normal)));
        count++;maxNormalError=max(maxNormalError,error);
        if(!isfinite(error)||error>.0001)failures++;
        uint history=packWaterNormalHistory(normal,normal);
        float3 stored=normalize(storeWaterGuideNormal(normal));
        count++;
        if(history!=WATER_NORMAL_UNCHANGED||any(previousWaterGuideNormal(stored,history)!=stored)
                ||packWaterGuideNormal(normal)==WATER_NORMAL_UNCHANGED)failures++;
        float3 current=normalize(float3(f16tof32(f32tof16(normal.x)),f16tof32(f32tof16(normal.y)),f16tof32(f32tof16(normal.z))));
        error=length(current-unpackWaterGuideNormal(packPreviousWaterGuideNormal(normal)));
        count++;maxNormalError=max(maxNormalError,error);
        if(!isfinite(error)||error>.0001)failures++;
        float3 oldNormal=normalize(normal+float3(.13,-.07,.11));
        history=packWaterNormalHistory(normal,oldNormal);
        error=length(normalize(storeWaterGuideNormal(oldNormal))-previousWaterGuideNormal(stored,history));
        count++;maxNormalError=max(maxNormalError,error);
        if(history==WATER_NORMAL_UNCHANGED||!isfinite(error)||error>.0001)failures++;
    }
    WaterPrimitive p;p.v0=float4(0,15.875,0,1);p.v1=float4(0,15.875,1,1);p.v2=float4(1,16,1,0);
    p.rectangle=float4(0,0,1,1);p.shore=float4(1);
    for(int cell=-1;cell<=1;cell++)for(int crest=-1;crest<=1;crest++) {
        float3 translation=float3(cell*4352,48,-cell*4352);
        float3 position=translation+float3(.25,15.90625+float(crest)*.9,.75);
        float3 owner=primitiveWaterOwnership(p,translation,position);
        count++;
        if(length(owner.xz-position.xz)>0||abs(owner.y-63.90625)>1e-5||floor(owner.y/16.0)!=3)failures++;
    }
    for(int shore=0;shore<3;shore++)for(int phase=0;phase<8;phase++) {
        p.shore=shore==0?float4(1):shore==1?float4(0):float4(0,1,1,1);
        WaterPatch patch=primitiveWaterPatch(p,float3(0));
        float time=float(phase)*.37,previous=time-.016,height,oldHeight;float2 gradient;
        patchField(patch,float2(.25,.75),time,0,height,gradient);
        patchField(patch,float2(.25,.75),previous,0,oldHeight,gradient);
        float3 position=float3(.25,15.90625+height,.75);
        WaterWaves waves=primitiveWaterWaves(p,float3(0),position,2,true,time,previous);
        float xp,xm,zp,zm;float2 unused;
        patchField(patch,float2(.251,.75),previous,0,xp,unused);patchField(patch,float2(.249,.75),previous,0,xm,unused);
        patchField(patch,float2(.25,.751),previous,0,zp,unused);patchField(patch,float2(.25,.749),previous,0,zm,unused);
        float3 expectedNormal=normalize(float3(-.125-(xp-xm)/.002,1,-(zp-zm)/.002));
        count++;
        if(length(waves.previousNormal-expectedNormal)>.001||abs(position.y-waves.motionPrev.y-15.90625-oldHeight)>1e-5
                ||length(waves.position-position)>0||length(waves.geometricNormal-waves.normal)>0)failures++;
        float savedSpeed=worldPush.waterOpacity.z;worldPush.waterOpacity.z=0;
        WaterWaves frozen=primitiveWaterWaves(p,float3(0),position,2,true,time,previous);
        worldPush.waterOpacity.z=savedSpeed;
        WaterWaves reset=primitiveWaterWaves(p,float3(0),position,2,true,time,time);
        count++;
        if(any(frozen.normal!=frozen.previousNormal)||any(frozen.motionPrev!=float3(0))
                ||any(reset.normal!=reset.previousNormal)||any(reset.motionPrev!=float3(0)))failures++;
    }
    uint reflectionCount;failures+=reflectionMotionChecks(reflectionCount);count+=reflectionCount;
    return failures;
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
        patch=probeGeometry((c>>3)&3u,(c>>5)&1u);
        uint work=0;bool exhausted=false,uncertain=false;float t=-1,b=-1;WaterPatch selectedPatch=patch;
        for(uint candidate=0;candidate<(GYSF_PATCH_SCENE?8u:1u);candidate++) {
            WaterPatch shape=GYSF_PATCH_SCENE?probeGeometry(candidate/2u,candidate&1u):patch;
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
        a=probeGeometry(2,0);b=probeGeometry(2,1);
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
    uint flatCount;result[18]=flatPrimitiveChecks(flatCount);result[19]=flatCount;
    uint runtimeCount;float maxNormalError;
    result[20]=runtimePrimitiveChecks(runtimeCount,maxNormalError);result[21]=runtimeCount;result[22]=asuint(maxNormalError);
}
