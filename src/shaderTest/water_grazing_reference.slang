import water;

// Independent dense sign-change search followed by bisection. Two sampling resolutions are compared
// in the probe. This is a bounded numerical reference, not a proof for all possible tangent roots.
public float denseWaterHit(float3 plane,float3 rd,float hitT,float time,float strength,float step,float footprint) {
    float bound=WAVE_MAX_HEIGHT*strength/abs(rd.y);
    float lo=rd.y<0?max(0.0,hitT-bound):hitT;
    float hi=rd.y<0?hitT:hitT+bound;
    float sign=rd.y<0?1.0:-1.0;
    float a=lo;
    for(int i=0;i<131072;i++) {
        float b=min(a+step,hi);
        float f=sign*(rd.y*(b-hitT)-waterWaveHeight(plane.xz+rd.xz*(b-hitT),time,footprint));
        if(f<=0.0) {
            for(int j=0;j<20;j++) {
                float mid=(a+b)*0.5;
                float fm=sign*(rd.y*(mid-hitT)-waterWaveHeight(plane.xz+rd.xz*(mid-hitT),time,footprint));
                if(fm>0.0) a=mid; else b=mid;
            }
            return (a+b)*0.5;
        }
        if(b==hi || b==a) break;
        a=b;
    }
    return -1.0;
}
