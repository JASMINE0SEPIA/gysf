import water;
import world_core;

// Non-runtime fixture setup for numerical surface/temporal identities. The separate domain probe
// obtains its coefficients and phases from production Java, including world-border coordinates.
public void setWaterTestDomain(float2 origin) {
    worldPush.waterAnchor=float4(0.0);
    for(int i=0;i<WAVE_COUNT;i++) {
        float4 axes=waterWaveBasis(i);
        worldPush.waterWaveDomains[i].axes=axes;
        worldPush.waterWaveDomains[i].phase=float4(dot(axes.xy,origin),dot(axes.zw,origin),waterWaveFrequency(i),0.0);
    }
}
