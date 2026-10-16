package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.compat.VoxyWaterDomain;
import java.util.List;

/** Immutable BDA image. All offsets are uint32 words; node words contain u16 clearance + known bit16. */
public final class RtWaterFieldImage {
    public static final int MAX_TILES=4096,HEADER_WORDS=4,ENTRY_WORDS=8;
    private RtWaterFieldImage() {}
    public static int hash(int x,int y,int z) {
        int h=x*0x8da6b343 ^ y*0xd8163841 ^ z*0xcb1ab31f;
        h^=h>>>16;h*=0x7feb352d;h^=h>>>15;return h;
    }
    public static int x(long key) { return (int)(key>>42); }
    public static int y(long key) { return (int)(key<<44>>44); }
    public static int z(long key) { return (int)(key<<22>>42); }

    /** Unique owners of every canonical node reached by these published top faces. */
    public static long[] domainTiles(RtWaterGeometry.Packed packed,int originX,int originY,int originZ) {
        var owners=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        float[] primitives=packed.primitives();
        for(int p=0;p<primitives.length;p+=RtWaterGeometry.PRIMITIVE_FLOATS) {
            if(primitives[p+3]==0)continue;
            int y=Math.addExact(originY,(int)primitives[p+11]);
            int x0=Math.addExact(originX,(int)Math.floor(primitives[p+12]));
            int z0=Math.addExact(originZ,(int)Math.floor(primitives[p+13]));
            int x1=Math.addExact(originX,Math.addExact((int)Math.floor(primitives[p+12]+primitives[p+14]),1));
            int z1=Math.addExact(originZ,Math.addExact((int)Math.floor(primitives[p+13]+primitives[p+15]),1));
            for(int x=Math.floorDiv(x0,16);x<=Math.floorDiv(x1,16);x++)
                for(int z=Math.floorDiv(z0,16);z<=Math.floorDiv(z1,16);z++)
                    owners.add(VoxyWaterDomain.Tile.key(x,y,z));
        }
        long[] result=owners.toLongArray();java.util.Arrays.sort(result);return result;
    }

    public static int[] pack(List<VoxyWaterDomain.Tile> tiles) {
        if(tiles.size()>MAX_TILES)throw new IllegalArgumentException("Water GPU residency exceeds tile budget");
        int slots=2;while(slots<tiles.size()*2)slots<<=1;
        int nodes=0;
        for(var tile:tiles)nodes+=constant(tile)?1:256;
        int[] words=new int[HEADER_WORDS+slots*ENTRY_WORDS+nodes];
        words[0]=slots-1;words[1]=tiles.size();words[2]=1;words[3]=words.length;
        int next=HEADER_WORDS+slots*ENTRY_WORDS;
        for(var tile:tiles) {
            int slot=hash(tile.x(),tile.y(),tile.z())&(slots-1);
            while(words[HEADER_WORDS+slot*ENTRY_WORDS+3]!=0) {
                int e=HEADER_WORDS+slot*ENTRY_WORDS;
                if(words[e]==tile.x()&&words[e+1]==tile.y()&&words[e+2]==tile.z())
                    throw new IllegalArgumentException("Duplicate water GPU tile");
                slot=(slot+1)&(slots-1);
            }
            int entry=HEADER_WORDS+slot*ENTRY_WORDS;
            words[entry]=tile.x();words[entry+1]=tile.y();words[entry+2]=tile.z();words[entry+3]=next;
            boolean constant=constant(tile);words[entry+4]=constant?1:0;
            var codes=tile.codes();int count=constant?1:256;
            for(int n=0;n<count;n++)words[next++]=(codes.get(codes.remaining()==1?0:n)&65535)
                    | (tile.known(n&15,n>>>4)?65536:0);
        }
        return words;
    }
    private static boolean constant(VoxyWaterDomain.Tile tile) {
        if(tile.codes().remaining()!=1)return false;
        long[] known=tile.knownMask();
        return (known[0]==0&&known[1]==0&&known[2]==0&&known[3]==0)||tile.complete();
    }
}
