package dev.comfyfluffy.caustica.compat;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.lang.reflect.Method;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/** Optional water-field capability 1, independent of the unchanged Voxy mesh API 6. */
public final class VoxyWaterDomain {
    private VoxyWaterDomain() {}
    private static final class Live {
        static final Reader READER=connect();
        static Reader connect() {
            try { return new Reader(Class.forName("me.cortex.voxy.client.compat.CausticaBridge")); }
            catch(ReflectiveOperationException missing) { return null; }
        }
    }
    public static boolean supported() { return Live.READER!=null; }
    public static Snapshot poll(int[] xyz) {
        if(Live.READER==null) return null;
        try { return Live.READER.poll(xyz); }
        catch(ReflectiveOperationException failure) { throw new IllegalStateException("Voxy water field export failed",failure); }
    }

    public static final class Tile {
        private final int x,y,z;
        private final long revision;
        private final short[] clearance;
        private final long[] known;
        public Tile(int x,int y,int z,long revision,short[] clearance,long[] known) {
            key(x,y,z);
            if((clearance.length!=1 && clearance.length!=256) || known.length!=4)
                throw new IllegalArgumentException("Invalid water field tile payload");
            this.x=x; this.y=y; this.z=z; this.revision=revision;
            this.clearance=clearance.clone(); this.known=known.clone();
        }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public long revision() { return revision; }
        public long key() { return key(x,y,z); }
        public ShortBuffer codes() { return ShortBuffer.wrap(clearance).asReadOnlyBuffer(); }
        public long[] knownMask() { return known.clone(); }
        public boolean known(int x,int z) { int index=index(x,z); return (known[index>>>6] & (1L<<(index&63)))!=0; }
        public boolean complete() { return known[0]==-1 && known[1]==-1 && known[2]==-1 && known[3]==-1; }
        public float clearance(int x,int z) {
            int index=index(x,z);
            return known(x,z) ? (clearance[clearance.length==1 ? 0 : index] & 65535)*(3f/65535f) : 0;
        }
        public static long key(int x,int y,int z) {
            if(x<-(1<<21) || x>=(1<<21) || z<-(1<<21) || z>=(1<<21) || y<-(1<<19) || y>=(1<<19))
                throw new IllegalArgumentException("Water tile coordinate outside packed world range");
            return ((long)x & 0x3fffffL)<<42 | ((long)z & 0x3fffffL)<<20 | (y & 0xfffffL);
        }
        private static int index(int x,int z) {
            if(x<0 || x>=16 || z<0 || z>=16) throw new IllegalArgumentException("Water node outside tile");
            return z*16+x;
        }
    }

    public static final class Snapshot {
        private final long epoch,revision;
        private final List<Tile> tiles;
        private final Long2ObjectOpenHashMap<Tile> index;
        private final int pending;
        public Snapshot(long epoch,long revision,List<Tile> tiles,int pending) {
            if(pending<0) throw new IllegalArgumentException("Negative water pending count");
            this.epoch=epoch; this.revision=revision; this.tiles=List.copyOf(tiles); this.pending=pending;
            index=new Long2ObjectOpenHashMap<>(tiles.size());
            for(var tile:tiles) if(index.put(tile.key(),tile)!=null) throw new IllegalArgumentException("Duplicate water tiles");
        }
        public long epoch() { return epoch; }
        public long revision() { return revision; }
        public List<Tile> tiles() { return tiles; }
        public int pending() { return pending; }
        public Tile tile(int x,int y,int z) { return index.get(Tile.key(x,y,z)); }
    }

    static final class Reader {
        private final Method poll,epoch,revision,tiles,pending;
        private Access access;
        private long currentEpoch=Long.MIN_VALUE,currentRevision;
        private Long2ObjectOpenHashMap<Tile> previous=new Long2ObjectOpenHashMap<>();
        private record Access(Class<?> type,Method x,Method y,Method z,Method revision,Method clearance,Method known) {
            static Access create(Class<?> type) throws ReflectiveOperationException {
                return new Access(type,type.getMethod("x"),type.getMethod("y"),type.getMethod("z"),
                        type.getMethod("revision"),type.getMethod("clearance"),type.getMethod("known"));
            }
        }
        Reader(Class<?> bridge) throws ReflectiveOperationException {
            if(!Integer.valueOf(1).equals(bridge.getMethod("waterDomainVersion").invoke(null)))
                throw new NoSuchMethodException("Unsupported water-domain capability");
            poll=bridge.getMethod("pollWaterDomain",int[].class);
            Class<?> scene=poll.getReturnType();
            epoch=scene.getMethod("epoch"); revision=scene.getMethod("revision"); tiles=scene.getMethod("tiles"); pending=scene.getMethod("pending");
        }
        Snapshot poll(int[] xyz) throws ReflectiveOperationException {
            if(xyz.length%3!=0 || xyz.length/3>4096) throw new IllegalArgumentException("Invalid water field request batch");
            var wanted=new LongOpenHashSet(xyz.length/3);
            for(int i=0;i<xyz.length;i+=3) wanted.add(Tile.key(xyz[i],xyz[i+1],xyz[i+2]));
            Object scene=poll.invoke(null,(Object)xyz);
            if(scene==null) { previous.clear(); currentEpoch=Long.MIN_VALUE; currentRevision=0; return null; }
            long nextEpoch=(long)epoch.invoke(scene),nextRevision=(long)revision.invoke(scene);
            if(nextEpoch!=currentEpoch) { previous.clear(); currentEpoch=nextEpoch; currentRevision=0; }
            if(nextRevision<currentRevision) throw new IllegalStateException("Voxy water field revision went backwards");
            var next=new Long2ObjectOpenHashMap<Tile>();
            var result=new ArrayList<Tile>();
            for(Object value:(List<?>)tiles.invoke(scene)) {
                if(access==null || access.type()!=value.getClass()) access=Access.create(value.getClass());
                int x=(int)access.x.invoke(value),y=(int)access.y.invoke(value),z=(int)access.z.invoke(value);
                long key=Tile.key(x,y,z),version=(long)access.revision.invoke(value);
                if(!wanted.contains(key)) throw new IllegalStateException("Provider returned an unrequested water tile");
                Tile copy=previous.get(key);
                if(version<=0 || (copy!=null && version<copy.revision()))
                    throw new IllegalStateException("Voxy water tile revision went backwards");
                if(copy==null || copy.revision()!=version) {
                    var codes=((ShortBuffer)access.clearance.invoke(value)).duplicate();
                    short[] packed=new short[codes.remaining()]; codes.get(packed);
                    copy=new Tile(x,y,z,version,packed,(long[])access.known.invoke(value));
                }
                Tile duplicate=next.put(key,copy);
                if(duplicate==null) result.add(copy);
                else if(duplicate.revision()!=version) throw new IllegalStateException("Conflicting water tile versions");
            }
            previous=next; currentRevision=nextRevision;
            return new Snapshot(currentEpoch,currentRevision,result,(int)pending.invoke(scene));
        }
    }
}
