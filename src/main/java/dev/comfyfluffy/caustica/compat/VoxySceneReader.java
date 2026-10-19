package dev.comfyfluffy.caustica.compat;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.*;
import java.nio.LongBuffer;
import java.util.*;
import it.unimi.dsi.fastutil.longs.*;

/** Version-checked reflection stays at the optional mod boundary; mesh workers read owned snapshots. */
final class VoxySceneReader {
    private final Method poll, epoch, meshes, models, colours, atlas, quads, ox, oy, oz, size;
    private final Field key, meshRevision, level;
    private final Method[] modelValues;
    private final Method fluidHeights;
    private final Method refinementPending;
    private boolean pendingRefinement;
    private Object previous, previousModels;
    private int[] previousColours;
    private VoxySceneData.Palette palette;
    private long currentEpoch = Long.MIN_VALUE;
    private final VoxyMeshCache meshCache = new VoxyMeshCache(256L << 20);
    private long cacheHits, returnHits;
    private final Long2ObjectOpenHashMap<Cached> cache = new Long2ObjectOpenHashMap<>();
    private List<DistantHorizonsCompat.LodMesh> published = List.of();
    private long atlasView;
    private Object atlasOwner=new Object();
    private record Cached(long version, DistantHorizonsCompat.LodMesh mesh) {}

    VoxySceneReader(Class<?> bridge) throws ReflectiveOperationException {
        poll = bridge.getMethod("pollScene", double.class, double.class, double.class);
        Class<?> scene = Class.forName("me.cortex.voxy.api.LodScene");
        epoch=scene.getMethod("epoch"); meshes=scene.getMethod("meshes"); models=scene.getMethod("models");
        colours=scene.getMethod("colours"); atlas=scene.getMethod("atlasView");
        Method pending=null;
        try {pending=scene.getMethod("refinementPending");}catch(NoSuchMethodException olderProvider) { }
        refinementPending=pending;
        Class<?> mesh=Class.forName("me.cortex.voxy.api.LodMesh");
        key=mesh.getField("key"); meshRevision=mesh.getField("revision"); level=mesh.getField("level");
        quads=mesh.getMethod("quads"); ox=mesh.getMethod("originX"); oy=mesh.getMethod("originY");
        oz=mesh.getMethod("originZ"); size=mesh.getMethod("blockSize");
        Method precision=null;
        try { precision=mesh.getMethod("fluidHeights"); }
        catch(NoSuchMethodException api5) { }
        fluidHeights=precision;
        Class<?> model=Class.forName("me.cortex.voxy.api.LodModel");
        String[] names={"id","down","up","north","south","west","east","flags","tint","state"};
        modelValues=new Method[names.length];
        for(int i=0;i<names.length;i++) modelValues[i]=model.getMethod(names[i]);
    }

    List<DistantHorizonsCompat.LodMesh> poll(double x,double y,double z) throws ReflectiveOperationException {
        Object scene=poll.invoke(null,x,y,z);
        if(scene==null) { clear(); return published; }
        if(scene==previous) return published;
        long nextEpoch=(long)epoch.invoke(scene);
        if(nextEpoch!=currentEpoch) { clear(); currentEpoch=nextEpoch; }
        pendingRefinement=refinementPending!=null && (boolean)refinementPending.invoke(scene);
        Object nextModels=models.invoke(scene);
        int[] nextColours=(int[])colours.invoke(scene);
        if(previousModels!=nextModels || !Arrays.equals(previousColours,nextColours)) {
            VoxySceneData.Model[] entries=new VoxySceneData.Model[65536];
            for(Object model:((Map<?,?>)nextModels).values()) {
                int[] v=new int[9];
                for(int i=0;i<v.length;i++) v[i]=(int)modelValues[i].invoke(model);
                BlockState state=(BlockState)modelValues[9].invoke(model);
                boolean fluid=(v[7]&16)!=0;
                boolean water=fluid && state.getFluidState().is(FluidTags.WATER);
                entries[v[0]]=new VoxySceneData.Model(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],state,
                        fluid,water,VoxySceneData.isGlass(v[7],fluid),state.getLightEmission());
            }
            palette=new VoxySceneData.Palette(entries,nextColours);
            previousModels=nextModels; previousColours=nextColours;
        }
        ArrayList<DistantHorizonsCompat.LodMesh> result=new ArrayList<>();
        LongOpenHashSet keep=new LongOpenHashSet();
        for(Object mesh:(List<?>)meshes.invoke(scene)) {
            long id=key.getLong(mesh), revision=meshRevision.getLong(mesh);
            keep.add(id);
            Cached cached=cache.get(id);
            VoxyMeshCache.Entry content=meshCache.get(id,revision,palette);
            if(content!=null) {
                cacheHits++;
                if(cached==null)returnHits++;
            } else {
                LongBuffer buffer=(LongBuffer)quads.invoke(mesh);
                long[] packed=new long[buffer.remaining()]; buffer.get(packed);
                int lod=level.getInt(mesh);
                long[] precise=new long[0];
                if(fluidHeights!=null && !Boolean.getBoolean("gysf.voxy.legacyFluidHeights")) {
                    LongBuffer values=(LongBuffer)fluidHeights.invoke(mesh);
                    precise=new long[values.remaining()]; values.get(precise);
                }
                content=meshCache.put(id,revision,packed,precise,lod,palette);
            }
            if(cached==null || cached.version!=content.version()) {
                var copy=new DistantHorizonsCompat.LodMesh(id,content.version(),(int)ox.invoke(mesh),(int)oy.invoke(mesh),
                        (int)oz.invoke(mesh),(int)size.invoke(mesh),1<<content.mesh().level(),new byte[0],new byte[0],content.mesh());
                cached=new Cached(content.version(),copy); cache.put(id,cached);
            }
            // Empty leaves are coverage too: they must retire previously visible solid geometry.
            result.add(cached.mesh);
        }
        cache.keySet().retainAll(keep);
        long view=(long)atlas.invoke(scene);
        if(view!=atlasView) atlasOwner=new Object();
        atlasView=view; previous=scene; published=List.copyOf(result);
        return published;
    }
    long atlasView() { return atlasView; }
    long sceneEpoch() { return currentEpoch; }
    boolean refinementPending() { return pendingRefinement; }
    Object atlasOwner() { return atlasOwner; }
    Map<String,Long> cacheStats() { return Map.of("hits",cacheHits,"returnHits",returnHits,
            "bytes",meshCache.bytes(),"sources",(long)meshCache.size()); }
    void clear() {
        cache.clear(); meshCache.clear(); previous=previousModels=null; previousColours=null; palette=null; published=List.of();
        currentEpoch=Long.MIN_VALUE; atlasView=0; atlasOwner=new Object();
        pendingRefinement=false;
    }
}
