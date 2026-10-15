import water;
import world_core;

// Candidate forward marching with a directional curvature bound. Residual checks alone do not prove
// closest-root accuracy. Keep outside production until that accuracy AND GPU cost are established.
public float strictWaveHit(float3 ro,float3 rd,float hitT,float3 n,float time,float footprint,out uint work,out bool exhausted) {
    float3 plane=ro+rd*hitT;
    float rate=dot(n,rd)/n.y;
    float lower=0.0,upper=0.0,curvature=0.0,lambda=WAVE_LAMBDA0;
    for(int i=0;i<WAVE_COUNT;i++) {
        if(i>=waterWaveCount()) break;
        float weight=waterWaveLodWeight(lambda,footprint);
        if(weight<=0) break;
        float a=weight*WAVE_Q[i]/(2.0*PI/lambda),S=1.15+0.08*float(i);
        float A=dot(worldPush.waterWaveDomains[i].axes.xy,rd.xz);
        float B=i<6?dot(worldPush.waterWaveDomains[i].axes.zw,rd.xz):0.0;
        float P=abs(A)+WAVE_MEANDER*abs(B),R=WAVE_MEANDER*B*B;
        lower+=a*exp(-2.0*S); upper+=a;
        // e*abs(-sin(phi)+S*cos(phi)^2)<=1 for S>=0; e*abs(cos(phi))<=1.
        curvature+=a*S*(P*P+R);
        lambda/=WAVE_LAMBDA_RATIO;
    }
    float strength=WATER_WAVE_STRENGTH*waterWaveStrengthScale();
    lower*=strength;upper*=strength;curvature=max(curvature*strength*1.0001,1e-7);
    work=0;exhausted=false;
    if(upper==0.0) return hitT;
    float lo=rate<0?max(0.0,hitT-(upper+1e-5)/abs(rate)):hitT+max(0.0,lower-1e-5)/rate;
    float hi=rate<0?hitT-max(0.0,lower-1e-5)/abs(rate):hitT+(upper+1e-5)/rate;
    float sign=rate<0?1.0:-1.0;
    float t=lo;
    for(int j=0;j<512;j++) {
        float height,hd;float2 grad,gd;
        waterWaveSpectrum<0>(plane.xz+rd.xz*(t-hitT),time,footprint,height,grad,hd,gd);
        float g=sign*(rate*(t-hitT)-height),dg=sign*(rate-dot(grad,rd.xz));
        work++;
        if(g<=1e-6) return t;
        float root=sqrt(dg*dg+2.0*curvature*g);
        float step=dg<0?2.0*g/(root-dg):(root+dg)/curvature;
        float next=min(t+max(step,0.0001),hi);
        if(next==t) return t;
        t=next;
    }
    exhausted=true;return t;
}
