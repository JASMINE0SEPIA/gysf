import water;
import world_core;

// Finite-water intersection module. A triangle's rest plane is affine; the original quad's four shore
// weights are shared by both halves. The gated game intersection stage and native probes share this module.
public struct WaterPatch {
    public float3 point;
    public float3 normal;
    public float2 minimum;
    public float2 extent;
    public float4 shore; // (x0z0,x0z1,x1z0,x1z1), each in [0,1]
    public uint half;    // 0: u>=v, 1: u<=v, 2: u+v<=1, 3: u+v>=1
};

public void patchWeight(WaterPatch patch,float2 p,out float weight,out float2 gradient) {
    float2 uv=clamp((p-patch.minimum)/patch.extent,0.0,1.0);
    float2 s=uv*uv*(3.0-2.0*uv),ds=6.0*uv*(1.0-uv)/patch.extent;
    float x0=lerp(patch.shore.x,patch.shore.y,s.y),x1=lerp(patch.shore.z,patch.shore.w,s.y);
    weight=lerp(x0,x1,s.x);
    gradient=float2((x1-x0)*ds.x,
        lerp(patch.shore.y-patch.shore.x,patch.shore.w-patch.shore.z,s.x)*ds.y);
}

public void patchField(WaterPatch patch,float2 p,float time,float footprint,out float h,out float2 gradient) {
    float height,dt,w;float2 grad,gradDt,dw;
    waterWaveSpectrum<0>(p,time,footprint,height,grad,dt,gradDt);
    patchWeight(patch,p,w,dw);
    h=height*w;gradient=grad*w+height*dw;
}

public bool clipPatchLinear(float value,float derivative,inout float lo,inout float hi) {
    if(derivative==0.0) return value>=0.0;
    float crossing=-value/derivative;
    if(derivative>0) lo=max(lo,crossing); else hi=min(hi,crossing);
    return lo<=hi;
}

public void patchBounds(WaterPatch patch,float2 ray,float footprint,out float lower,out float upper,out float curvature) {
    float hLo=0.0,hHi=0.0,slope=0.0,bend=0.0,lambda=WAVE_LAMBDA0;
    for(int i=0;i<WAVE_COUNT;i++) {
        if(i>=waterWaveCount()) break;
        float lod=waterWaveLodWeight(lambda,footprint);if(lod<=0)break;
        float a=lod*WAVE_Q[i]/(2.0*PI/lambda),S=1.15+0.08*float(i);
        float A=dot(worldPush.waterWaveDomains[i].axes.xy,ray);
        float B=i<6?dot(worldPush.waterWaveDomains[i].axes.zw,ray):0.0;
        float P=abs(A)+WAVE_MEANDER*abs(B),R=WAVE_MEANDER*B*B;
        hLo+=a*exp(-2.0*S);hHi+=a;slope+=a*S*P;bend+=a*S*(P*P+R);
        lambda/=WAVE_LAMBDA_RATIO;
    }
    float strength=WATER_WAVE_STRENGTH*waterWaveStrengthScale();
    hLo*=strength;hHi*=strength;slope*=strength;bend*=strength;
    float minimum=min(min(patch.shore.x,patch.shore.y),min(patch.shore.z,patch.shore.w));
    float maximum=max(max(patch.shore.x,patch.shore.y),max(patch.shore.z,patch.shore.w));
    float dx=max(abs(patch.shore.z-patch.shore.x),abs(patch.shore.w-patch.shore.y));
    float dz=max(abs(patch.shore.y-patch.shore.x),abs(patch.shore.w-patch.shore.z));
    float cross=abs(patch.shore.w-patch.shore.z-patch.shore.y+patch.shore.x);
    float2 v=abs(ray/patch.extent);
    float dWeight=1.5*(dx*v.x+dz*v.y);
    float ddWeight=6.0*dx*v.x*v.x+4.5*cross*v.x*v.y+6.0*dz*v.y*v.y;
    lower=hLo*minimum;upper=hHi*maximum;
    curvature=max((bend*maximum+2.0*slope*dWeight+hHi*ddWeight)*1.0001,1e-7);
}

public bool patchInterval(WaterPatch patch,float3 ro,float3 rd,float lower,float upper,inout float lo,inout float hi) {
    float2 uv=(ro.xz-patch.minimum)/patch.extent,d=rd.xz/patch.extent;
    if(!clipPatchLinear(uv.x,d.x,lo,hi)||!clipPatchLinear(1.0-uv.x,-d.x,lo,hi)
        ||!clipPatchLinear(uv.y,d.y,lo,hi)||!clipPatchLinear(1.0-uv.y,-d.y,lo,hi))return false;
    float sign=patch.half==0||patch.half==3?1.0:-1.0;
    float diagonal=patch.half<2?uv.x-uv.y:uv.x+uv.y-1.0;
    float diagonalRate=patch.half<2?d.x-d.y:d.x+d.y;
    if(!clipPatchLinear(sign*diagonal,sign*diagonalRate,lo,hi))return false;
    float offset=dot(patch.normal,ro-patch.point)/patch.normal.y,rate=dot(patch.normal,rd)/patch.normal.y;
    if(upper==0.0 && rate==0.0)return false; // no isolated crossing for a coplanar flat ray
    return clipPatchLinear(offset-lower,rate,lo,hi)&&clipPatchLinear(upper-offset,-rate,lo,hi);
}

public float patchEquation(WaterPatch patch,float3 ro,float3 rd,float t,float time,float footprint,out float derivative) {
    float h;float2 gradient;patchField(patch,(ro+rd*t).xz,time,footprint,h,gradient);
    float rate=dot(patch.normal,rd)/patch.normal.y;
    derivative=rate-dot(gradient,rd.xz);
    return dot(patch.normal,ro-patch.point)/patch.normal.y+rate*t-h;
}

public float intersectWaterPatch(WaterPatch patch,float3 ro,float3 rd,float time,float footprint,
        float rayMin,float rayMax,out uint work,out bool exhausted) {
    float lower,upper,curvature;patchBounds(patch,rd.xz,footprint,lower,upper,curvature);
    float lo=rayMin,hi=rayMax;work=0;exhausted=false;
    if(!patchInterval(patch,ro,rd,lower,upper,lo,hi))return -1.0;
    float t=lo,last=lo,sign=1.0;
    for(int i=0;i<512;i++) {
        float derivative,f=patchEquation(patch,ro,rd,t,time,footprint,derivative);work++;
        if(i==0)sign=f>=0.0?1.0:-1.0;
        if(abs(f)<=1e-6)return t;
        float g=sign*f,dg=sign*derivative;
        if(g<0.0) {
            // A rounding-sized overshoot brackets the first crossing; refine within that bracket.
            float a=last,b=t;
            for(int j=0;j<16;j++) {
                float mid=(a+b)*0.5,dummy,fm=patchEquation(patch,ro,rd,mid,time,footprint,dummy);work++;
                if(sign*fm>0.0)a=mid;else b=mid;
            }
            return (a+b)*0.5;
        }
        if(t==hi)return -1.0;
        float root=sqrt(dg*dg+2.0*curvature*g);
        float step=dg<0.0?2.0*g/(root-dg):(root+dg)/curvature;
        float next=min(t+max(step,0.00001),hi);
        if(next==t){exhausted=true;return -1.0;}
        last=t;t=next;
    }
    exhausted=true;return -1.0;
}
