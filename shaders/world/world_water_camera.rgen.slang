// One invocation before the image passes. Probe the same finite geometry rather than evaluating
// a second CPU wave field or doing another wave evaluation for every screen pixel.
import world_common;
import world_core;
import trace;
import math;

[shader("raygeneration")]
void main() {
    worldPush=ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    uint submerged=worldPush.waterCamera.x;
    uint flags=submerged!=0u ? 16u : 0u; // retain the CPU rest-surface hint for diagnostics
    float3 eye;
    if(!cameraProjectionEye(eye))return;
    float upT=-1.0,downT=-1.0;
    bool upWet=false,downWet=false;
    // Above/below probes stop at the first visible interface, including solid/glass blockers.
    // CULL_PRIMARY excludes the first-person body; guide miss avoids the sky atmosphere march.
    // Outside this short surface neighborhood, retain the CPU deep/covered-water classification.
    traceGuide(CULL_PRIMARY,eye,0.0001,float3(0,1,0),3.0,0.0,0.0);
    if(payload.hitT>=0.0 && payloadMaterial()==MATERIAL_WATER && (payload.flags&PAYLOAD_FINITE_WATER)!=0u) {
        upT=payload.hitT;upWet=!payloadDielectricEntering();
        flags|=1u|(upWet?4u:0u);
    }
    traceGuide(CULL_PRIMARY,eye,0.0001,float3(0,-1,0),3.0,0.0,0.0);
    if(payload.hitT>=0.0 && payloadMaterial()==MATERIAL_WATER && (payload.flags&PAYLOAD_FINITE_WATER)!=0u) {
        downT=payload.hitT;downWet=!payloadDielectricEntering();
        flags|=2u|(downWet?8u:0u);
    }
    if(upT>=0.0 && downT>=0.0 && upWet!=downWet) flags|=32u;
    if(upT>=0.0 && (downT<0.0 || upT<=downT)) submerged=upWet?1u:0u;
    else if(downT>=0.0) submerged=downWet?1u:0u;
    DevicePtr<WorldPush>(pc.worldPushAddr)[0].waterCamera=uint4(submerged|2u,flags,asuint(upT),asuint(downT));
}
