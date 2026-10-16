import world_common;
import world_core;
import water;
import water_patch;
import water_field;
import water_primitive;
import water_test_frame;

[[vk::binding(100,0)]] RWStructuredBuffer<float4> fieldResults;
[[vk::binding(101,0)]] StructuredBuffer<uint64_t> fieldAddresses;

// Independent fixture oracle, not a read through the packed table or patch interpolation helpers.
uint expectedNode(int2 p,int y) {
    bool border=p.x>=29999984&&p.x<=30000031&&p.y>=-30000016&&p.y<=-29999969;
    if(y!=175||(!border&&any(p<int2(-32)||p>int2(47))))return 0;
    uint code=border?uint(p.x&15)*4096u:p.x<=0?0u:p.x==1?6554u:65535u;
    bool known=border?((p.x&15)!=7&&(p.y&15)!=7):(p.x!=7&&p.y!=7);
    return code|(known?65536u:0u);
}
float expectedWeight(int2 p) {
    uint word=expectedNode(p,175);
    return (word&65536u)==0?0.0:min(float(word&65535u)*(3.0/65535.0)/(.55*waterWaveStrengthScale()),1.0);
}
float referenceWeight(float2 local,int2 anchor) {
    int2 cell=int2(floor(local));float2 f=local-float2(cell);
    float u=f.x*f.x*(3-2*f.x),v=f.y*f.y*(3-2*f.y);
    float a=expectedWeight(anchor+cell),b=expectedWeight(anchor+cell+int2(0,1));
    float c=expectedWeight(anchor+cell+int2(1,0)),d=expectedWeight(anchor+cell+1);
    return (1-u)*((1-v)*a+v*b)+u*((1-v)*c+v*d);
}
float referenceEquation(float3 ro,float3 rd,float t,float time) {
    float3 p=ro+rd*t;float h,dt;float2 gradient,gradientDt;
    waterWaveSpectrum<0>(p.xz,time,0,h,gradient,dt,gradientDt);
    return p.y-h*referenceWeight(p.xz,int2(0));
}
float referenceRoot(float3 ro,float3 rd,float time,float2 minimum) {
    float a=0,b=40;
    if(!clipPatchLinear(ro.x-minimum.x,rd.x,a,b)||!clipPatchLinear(minimum.x+16-ro.x,-rd.x,a,b)
        ||!clipPatchLinear(ro.z-minimum.y,rd.z,a,b)||!clipPatchLinear(minimum.y+16-ro.z,-rd.z,a,b))return -1;
    float last=a,f=referenceEquation(ro,rd,a,time);
    if(abs(f)<1e-7)return a;
    for(int i=0;i<20001;i++) {
        float next=min(a+float(i+1)*.002,b),g=referenceEquation(ro,rd,next,time);
        if(f*g<=0) {
            for(int j=0;j<24;j++) {
                float mid=(last+next)*.5,m=referenceEquation(ro,rd,mid,time);
                if(f*m>0){last=mid;f=m;}else next=mid;
            }
            return (last+next)*.5;
        }
        if(next==b)return -1;
        last=next;f=g;
    }
    return -2;
}

[shader("compute")]
[numthreads(64,1,1)]
void fieldProbe(uint3 id:SV_DispatchThreadID) {
    uint c=id.x;if(c>=4096)return;
    worldPush.waterFieldAddr=fieldAddresses[0];worldPush.waterPreviousFieldAddr=fieldAddresses[1];
    worldPush.waterOpacity=float4(0,(c&1)==0?1.0:4.0,1,7);
    setWaterTestDomain(float2(0));worldPush.sharcGridOrigin=int4(0);
    uint failures=0,work=0;float error=0,gradientError=0;
    if(c<3840) {
        bool border=(c&4)!=0;
        int2 anchor=border?int2(30000000,-30000000):int2(0);
        float2 p=float2(float(int(c%47)-23)+float((c*7)%31)/32,
                       float(int((c*11)%47)-23)+float((c*13)%31)/32);
        int2 node=anchor+int2(floor(p));
        if(waterNodeWord(worldPush.waterFieldAddr,node,175)!=expectedNode(node,175))failures++;
        if(waterNodeWord(worldPush.waterFieldAddr,node,-1)!=0)failures++;
        WaterPatch patch;patch.point=0;patch.normal=float3(0,1,0);patch.minimum=0;patch.extent=16;patch.shore=1;patch.half=0;
        worldPush.sharcGridOrigin=int4(anchor.x,0,anchor.y,0);
        WaterPatch cell=canonicalWaterCell(patch,worldPush.waterFieldAddr,p,175);
        float actual;float2 gradient;patchWeight(cell,p,actual,gradient);
        error=abs(actual-referenceWeight(p,anchor));if(error>1e-6)failures++;
        float2 xp=p+float2(.0005,0),xm=p-float2(.0005,0),zp=p+float2(0,.0005),zm=p-float2(0,.0005);
        float2 expectedGrad=float2((referenceWeight(xp,anchor)-referenceWeight(xm,anchor))/(xp.x-xm.x),
                (referenceWeight(zp,anchor)-referenceWeight(zm,anchor))/(zp.y-zm.y));
        gradientError=length(gradient-expectedGrad);
        if(gradientError>.002)failures++;
        worldPush.sharcGridOrigin=int4(anchor.x+64,0,anchor.y-64,0);
        WaterPatch rebased=canonicalWaterCell(patch,worldPush.waterFieldAddr,p+float2(-64,64),175);
        float moved;float2 movedGradient;patchWeight(rebased,p+float2(-64,64),moved,movedGradient);
        if(actual!=moved||any(gradient!=movedGradient))failures++;
    } else {
        uint r=c-3840;float time=float(r%13)*.317;
        float3 ro,rd;
        if((r&3)==0) {ro=float3(.125+float(r%16),3,.375+float((r*7)%16));rd=float3(0,-1,0);}
        else {
            bool reverse=(r&1)!=0;ro=float3(reverse?17:-1,.05+float(r%7)*.12,.2+float((r*7)%15));
            rd=normalize(float3(reverse?-1:1,(r&2)!=0?-.01:.01,float(int(r%3)-1)*.03));
        }
        float2 minimum=r<128?float2(0):r<192?float2(16):float2(-16);
        ro.xz+=minimum;
        WaterPatch patch;patch.point=0;patch.normal=float3(0,1,0);patch.minimum=minimum;patch.extent=16;patch.shore=1;
        float hit=-1;bool exhausted=false;
        for(uint half=0;half<2;half++) {
            patch.half=half;uint n;bool outOfWork;
            float t=intersectCanonicalWaterPatch(patch,175,ro,rd,time,0,0,40,n,outOfWork);
            work+=n;exhausted=exhausted||outOfWork;
            if(t>=0&&(hit<0||t<hit))hit=t;
        }
        float reference=referenceRoot(ro,rd,time,minimum);
        if(exhausted||reference== -2||(hit<0)!=(reference<0))failures++;
        if(hit>=0) {
            error=abs(hit-reference);
            if(error>.002||abs(referenceEquation(ro,rd,hit,time))>2e-5)failures++;
            // A unit near primitive enclosing that root must report the identical physical surface.
            patch.minimum=floor((ro+rd*hit).xz);patch.extent=1;float fine=-1;
            for(uint half=0;half<2;half++) {
                patch.half=half;uint n;bool outOfWork;
                float t=intersectCanonicalWaterPatch(patch,175,ro,rd,time,0,0,40,n,outOfWork);
                if(outOfWork)failures++;
                if(t>=0&&(fine<0||t<fine))fine=t;
            }
            if(fine<0||abs(fine-hit)>.002)failures++;
            float3 position=ro+rd*hit;
            WaterPrimitive primitive;primitive.v0=float4(0,0,0,1);primitive.v1=float4(0,0,16,1);
            primitive.v2=float4(16,0,16,175);primitive.rectangle=float4(0,0,16,16);primitive.shore=1;
            WaterWaves waves=primitiveWaterWaves(primitive,float3(minimum.x,0,minimum.y),position,hit,true,time,time);
            if(length(waves.previousNormal-float3(0,1,0))>1e-6||abs(waves.motionPrev.y-position.y)>2e-5)failures++;
        }
        if(r>=224) for(int distanceBand=0;distanceBand<3;distanceBand++) {
            // Unknown distant cells are exactly flat. Their analytic root need not leave a
            // sub-micrometre residual when multiplied back through float ray coordinates.
            float side=(r&1)==0?1.0:-1.0;
            float3 farOrigin=float3(.125,side*77.25,.375);
            float slopeScale=distanceBand==0?1.0:distanceBand==1?16.0:128.0;
            float3 farDirection=normalize(float3((r&2)==0?1.0:-1.0,
                    -side*(.005+float(r-224)*.0001)*slopeScale,(r&4)==0?.37:-.37));
            float expected=-farOrigin.y/farDirection.y;
            float2 position=(farOrigin+farDirection*expected).xz;
            patch.half=(r&8)==0?0:1;
            patch.minimum=position-(patch.half==0?float2(12,4):float2(4,12));patch.extent=16;
            uint n;bool outOfWork;
            float farHit=intersectCanonicalWaterPatch(patch,175,farOrigin,farDirection,time,0,0,expected+100,n,outOfWork);
            float ulp=asfloat(asuint(expected)+1u)-expected;
            if(farHit<0||outOfWork||abs(farHit-expected)>2*ulp)failures++;
            work+=n;
            // The same flat plane through the ordinary patch route must agree, including both
            // ray-limit endpoints. Clipping outside the ray or triangle must still reject it.
            patch.shore=0;
            float direct=intersectWaterPatch(patch,farOrigin,farDirection,time,0,0,expected+100,n,outOfWork);
            if(direct<0||outOfWork||abs(direct-expected)>2*ulp)failures++;
            float limited=intersectCanonicalWaterPatch(patch,175,farOrigin,farDirection,time,0,expected,expected,n,outOfWork);
            if(limited<0||outOfWork||abs(limited-expected)>2*ulp)failures++;
            float excluded=intersectCanonicalWaterPatch(patch,175,farOrigin,farDirection,time,0,expected+8*ulp,expected+100,n,outOfWork);
            if(excluded>=0||outOfWork)failures++;
            excluded=intersectCanonicalWaterPatch(patch,175,farOrigin,farDirection,time,0,0,expected-8*ulp,n,outOfWork);
            if(excluded>=0||outOfWork)failures++;
            patch.half=1-patch.half;
            excluded=intersectCanonicalWaterPatch(patch,175,farOrigin,farDirection,time,0,0,expected+100,n,outOfWork);
            if(excluded>=0||outOfWork)failures++;
        }
    }
    fieldResults[c]=float4(float(failures),error,c<3840?gradientError:float(work),c<3840?0.0:1.0);
}
