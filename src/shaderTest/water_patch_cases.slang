import water;
import water_patch;
import world_core;

public WaterPatch waterPatchGeometry(uint sample,uint half) {
    WaterPatch patch;
    patch.minimum=float2(float(int(sample)*3-6),float(int(sample)*5-9));patch.extent=float2(1.0);
    patch.point=float3(patch.minimum.x,112.0,patch.minimum.y);
    // Sample 2 is a nonplanar quad: its halves share h(u,u)=.4u but have different rest slopes.
    patch.normal=normalize(sample==1?float3(-0.4,1.0,0.0):sample==2?
        (half==0?float3(.2,1,-.6):float3(-.1,1,-.3)):float3(0,1,0));
    patch.shore=sample==0?float4(1):sample==1?float4(0,0,1,1):sample==2?float4(0,1,1,0):float4(0);
    patch.half=half;return patch;
}

// Shared input corpus only. CPU dense-reference and GPU candidate solve these rays independently.
public void waterPatchCase(uint c,out WaterPatch patch,out float3 ro,out float3 rd,
        out float time,out float footprint) {
    uint fi=c&3u,side=(c>>2)&1u,sample=(c>>3)&3u,ti=(c>>5)&7u,ai=(c>>8)&7u,si=(c>>11)&1u;
    float angles[8]={0.0,0.5,1.0,2.0,4.0,8.0,30.0,90.0};
    float strength=si==0?1.0:4.0;worldPush.waterOpacity=float4(1.0,strength,1.0,7.0);
    patch=waterPatchGeometry(sample,ti&1u);
    float2 centre=patch.minimum+(patch.half==0?float2(0.65,0.35):float2(0.35,0.65));
    float2 horizontal=normalize(float2(1.0,float(sample)*0.37));float theta=angles[ai]*PI/180.0;
    rd=float3(horizontal.x*cos(theta),(side==0?-1.0:1.0)*sin(theta),horizontal.y*cos(theta));
    float base=patch.point.y-dot(patch.normal.xz,centre-patch.point.xz)/patch.normal.y;
    ro=float3(centre.x,base+(side==0?0.15:-0.15)*strength,centre.y)-rd*2.0;
    time=float(ti)*1.37;footprint=fi==0?0.0:fi==1?0.5:fi==2?2.0:8.0;
}
