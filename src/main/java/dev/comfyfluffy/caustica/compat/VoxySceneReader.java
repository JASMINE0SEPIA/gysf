package dev.comfyfluffy.caustica.compat;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.*;
import java.nio.LongBuffer;
import java.util.*;

/** Version-checked reflection stays at the optional mod boundary; mesh workers read owned snapshots. */
final class VoxySceneReader {
    private final Method poll, epoch, meshes, models, colours, atlas, quads, ox, oy, oz, size;
    private final Field key, meshRevision, level;
    private final Method[] modelValues;
    private Object previous, previousModels;
    private VoxySceneData.Palette palette;
    private long currentEpoch = Long.MIN_VALUE, version;
    private final Map<Long, Cached> cache = new HashMap<>();
    private List<DistantHorizonsCompat.LodMesh> published = List.of();
    private long atlasView;
    private Object atlasOwner=new Object();
    private record Cached(long revision, DistantHorizonsCompat.LodMesh mesh) {}

    VoxySceneReader(Class<?> bridge) throws ReflectiveOperationException {
        poll = bridge.getMethod("pollScene", double.class, double.class, double.class);
        Class<?> scene = Class.forName("me.cortex.voxy.api.LodScene");
        epoch=scene.getMethod("epoch"); meshes=scene.getMethod("meshes"); models=scene.getMethod("models");
        colours=scene.getMethod("colours"); atlas=scene.getMethod("atlasView");
        Class<?> mesh=Class.forName("me.cortex.voxy.api.LodMesh");
        key=mesh.getField("key"); meshRevision=mesh.getField("revision"); level=mesh.getField("level");
        quads=mesh.getMethod("quads"); ox=mesh.getMethod("originX"); oy=mesh.getMethod("originY");
        oz=mesh.getMethod("originZ"); size=mesh.getMethod("blockSize");
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
        Object nextModels=models.invoke(scene);
        if(previousModels!=nextModels) {
            VoxySceneData.Model[] entries=new VoxySceneData.Model[65536];
            for(Object model:((Map<?,?>)nextModels).values()) {
                int[] v=new int[9];
                for(int i=0;i<v.length;i++) v[i]=(int)modelValues[i].invoke(model);
                BlockState state=(BlockState)modelValues[9].invoke(model);
                boolean fluid=(v[7]&16)!=0;
                boolean water=fluid && state.getFluidState().is(FluidTags.WATER);
                entries[v[0]]=new VoxySceneData.Model(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],state,
                        fluid,water,!water && (v[7]&4)!=0,state.getLightEmission());
            }
            palette=new VoxySceneData.Palette(entries,(int[])colours.invoke(scene));
            previousModels=nextModels; cache.clear();
        }
        ArrayList<DistantHorizonsCompat.LodMesh> result=new ArrayList<>();
        Set<Long> keep=new HashSet<>();
        for(Object mesh:(List<?>)meshes.invoke(scene)) {
            long id=key.getLong(mesh), revision=meshRevision.getLong(mesh);
            keep.add(id);
            Cached cached=cache.get(id);
            if(cached==null || cached.revision!=revision) {
                LongBuffer buffer=(LongBuffer)quads.invoke(mesh);
                long[] packed=new long[buffer.remaining()]; buffer.get(packed);
                int lod=level.getInt(mesh);
                var data=new VoxySceneData.Mesh(packed,palette,lod);
                var copy=new DistantHorizonsCompat.LodMesh(id,++version,(int)ox.invoke(mesh),(int)oy.invoke(mesh),
                        (int)oz.invoke(mesh),(int)size.invoke(mesh),1<<lod,new byte[0],new byte[0],data);
                cached=new Cached(revision,copy); cache.put(id,cached);
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
    Object atlasOwner() { return atlasOwner; }
    void clear() {
        cache.clear(); previous=previousModels=null; palette=null; published=List.of();
        currentEpoch=Long.MIN_VALUE; atlasView=0; atlasOwner=new Object();
    }
}
