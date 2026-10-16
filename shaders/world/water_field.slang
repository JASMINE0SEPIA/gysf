import world_core;
import world_common;
import water;
import water_patch;

// RtWaterFieldImage uses uint words throughout; this layout has no implementation-specific padding.
uint fieldWord(uint64_t address,uint index) {
    __target_switch {
    case cpp:
        __intrinsic_asm "((const uint32_t*)($0))[$1]";
    default:
        return ConstPtr<uint>(address)[index];
    }
}
public uint waterTileHash(int3 key) {
    uint h=uint(key.x)*0x8da6b343u ^ uint(key.y)*0xd8163841u ^ uint(key.z)*0xcb1ab31fu;
    h^=h>>16;h*=0x7feb352du;h^=h>>15;return h;
}
public uint waterTileEntry(uint64_t address,int3 tile) {
    uint mask=fieldWord(address,0),slot=waterTileHash(tile)&mask;
    for(uint probe=0;probe<=mask;probe++) {
        uint entry=4u+slot*8u;
        if(fieldWord(address,entry+3)==0)return 0;
        if(all(int3(asint(fieldWord(address,entry)),asint(fieldWord(address,entry+1)),asint(fieldWord(address,entry+2)))==tile))return entry;
        slot=(slot+1u)&mask;
    }
    return 0;
}
public uint waterNodeWord(uint64_t address,int2 node,int sourceY) {
    int2 owner=node>>4;
    uint entry=waterTileEntry(address,int3(owner.x,sourceY,owner.y));
    if(entry==0)return 0;
    uint index=fieldWord(address,entry+4)!=0?0u:uint((node.y&15)*16+(node.x&15));
    return fieldWord(address,fieldWord(address,entry+3)+index);
}
public float waterNodeWeight(uint word) {
    float bound=0.55*waterWaveStrengthScale();
    return (word&65536u)==0||bound==0?0.0:min(float(word&65535u)*(3.0/65535.0)/bound,1.0);
}
public float4 waterCellWeights(uint64_t address,int2 worldCell,int sourceY) {
    return float4(waterNodeWeight(waterNodeWord(address,worldCell,sourceY)),
        waterNodeWeight(waterNodeWord(address,worldCell+int2(0,1),sourceY)),
        waterNodeWeight(waterNodeWord(address,worldCell+int2(1,0),sourceY)),
        waterNodeWeight(waterNodeWord(address,worldCell+int2(1,1),sourceY)));
}
public WaterPatch canonicalWaterCell(WaterPatch patch,uint64_t address,float2 local,int sourceY) {
    int2 cell=int2(floor(local));
    patch.minimum=float2(cell);patch.extent=float2(1);
    patch.shore=waterCellWeights(address,worldPush.sharcGridOrigin.xz+cell,sourceY);
    return patch;
}

// Skip the 15x15 interior of a uniformly known/unknown tile; its outermost unit strips still
// interpolate the neighboring owners. This preserves exact unit-cell sampling at tile borders.
public bool constantWaterRegion(uint64_t address,int2 cell,int sourceY,out float weight,
        out float2 minimum,out float2 maximum) {
    int2 worldCell=worldPush.sharcGridOrigin.xz+cell,owner=worldCell>>4,within=worldCell&15;
    weight=0;minimum=float2(cell);maximum=minimum+1;
    if(any(within>=15))return false;
    uint entry=waterTileEntry(address,int3(owner.x,sourceY,owner.y));
    if(entry!=0) {
        if(fieldWord(address,entry+4)==0)return false;
        weight=waterNodeWeight(fieldWord(address,fieldWord(address,entry+3)));
    }
    minimum=float2(owner*16-worldPush.sharcGridOrigin.xz);maximum=minimum+15;
    return true;
}

public float intersectCanonicalWaterPatch(WaterPatch original,int sourceY,float3 ro,float3 rd,
        float time,float footprint,float rayMin,float rayMax,out uint work,out bool exhausted) {
    float lower,upper,curvature;WaterPatch broad=original;broad.shore=float4(1);
    patchBounds(broad,rd.xz,footprint,lower,upper,curvature);
    float lo=rayMin,hi=rayMax;work=0;exhausted=false;
    if(!patchInterval(broad,ro,rd,0,upper,lo,hi))return -1;
    float2 start=(ro+rd*lo).xz;
    int2 cell=int2(floor(start));
    if(rd.x<0&&start.x==float(cell.x))cell.x--;
    if(rd.z<0&&start.y==float(cell.y))cell.y--;
    for(int step=0;step<2048;step++) {
        WaterPatch patch=original;patch.minimum=float2(cell);patch.extent=float2(1);
        float2 minimum=float2(cell),maximum=minimum+1;float weight;
        if(constantWaterRegion(worldPush.waterFieldAddr,cell,sourceY,weight,minimum,maximum))patch.shore=float4(weight);
        else patch.shore=waterCellWeights(worldPush.waterFieldAddr,worldPush.sharcGridOrigin.xz+cell,sourceY);
        float tx=rd.x==0?1e30:((rd.x>0?maximum.x:minimum.x)-ro.x)/rd.x;
        float tz=rd.z==0?1e30:((rd.z>0?maximum.y:minimum.y)-ro.z)/rd.z;
        float end=min(hi,min(tx,tz));
        if(end>=lo) {
            patchBounds(patch,rd.xz,footprint,lower,upper,curvature);
            float a=lo,b=end;
            float offset=dot(patch.normal,ro-patch.point)/patch.normal.y,rate=dot(patch.normal,rd)/patch.normal.y;
            if(!(upper==0&&rate==0)&&clipPatchLinear(offset-lower,rate,a,b)&&clipPatchLinear(upper-offset,-rate,a,b)) {
                uint cellWork;bool cellExhausted;
                float hit=solveWaterPatchInterval(patch,ro,rd,time,footprint,a,b,curvature,cellWork,cellExhausted);
                work+=cellWork;
                if(cellExhausted){exhausted=true;return -1;}
                if(hit>=0)return hit;
            }
        }
        if(end>=hi)return -1;
        // No epsilon advance: both adjacent cells include the exact shared boundary.
        if(tx<=tz)cell.x=rd.x>0?int(maximum.x):int(minimum.x)-1;
        if(tz<=tx)cell.y=rd.z>0?int(maximum.y):int(minimum.y)-1;
        lo=max(lo,end);
    }
    exhausted=true;return -1;
}
