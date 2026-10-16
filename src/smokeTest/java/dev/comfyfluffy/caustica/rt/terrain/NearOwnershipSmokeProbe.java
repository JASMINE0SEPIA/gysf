package dev.comfyfluffy.caustica.rt.terrain;

import java.nio.*;
import java.lang.reflect.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Check the shader mask against actual published TLAS inputs after rendering, during window changes. */
public final class NearOwnershipSmokeProbe {
    private static long nextCheck,checks;
    private static long finerChecks;
    private static long nestedChecks;
    private static java.util.List<VoxyLodTransition.Volume> beforeAway=java.util.List.of();
    private static java.util.List<VoxyLodTransition.Volume> coarsened=java.util.List.of();
    private record Cube(int x,int y,int z,int width) {}
    private static final ByteBuffer MASK=ByteBuffer.allocate(1024*1024).order(ByteOrder.nativeOrder());
    public static void frame() {
        long now=System.nanoTime();
        if(now<nextCheck)return;
        nextCheck=now+1_000_000_000L;
        var near=RtTerrain.currentOrNull();if(near==null)return;
        try {
            var writer=RtTerrain.class.getDeclaredMethod("writeReadyMask",ByteBuffer.class);writer.setAccessible(true);
            if((int)writer.invoke(near,MASK)==0)return;
            var table=(RtSectionTable)field(near,"table");
            var empty=(LongOpenHashSet)field(near,"empty");
            var geometry=new LongOpenHashSet();
            int sx=MASK.getInt(12),sy=MASK.getInt(16),sz=MASK.getInt(20);
            int mx=(MASK.getInt(0)+near.blockX)>>4,my=(MASK.getInt(4)+near.blockY)>>4,mz=(MASK.getInt(8)+near.blockZ)>>4;
            for(var instance:near.staticInstances()) {
                var section=table.slots.get(instance.customIndex());geometry.add(section.key);
                float[] transform=instance.transform3x4();
                if(transform[3]+near.blockX!=section.sx || transform[7]+near.blockY!=section.sy || transform[11]+near.blockZ!=section.sz)
                    throw new IllegalStateException("Published near instance has inconsistent rebase transform");
            }
            for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++) {
                int bit=(y*sz+z)*sx+x;
                boolean actual=(MASK.getInt(32+(bit>>>5)*4)&(1<<(bit&31)))!=0;
                long key=RtTerrain.sectionKey(mx+x,my+y,mz+z);
                boolean expected=geometry.contains(key)||empty.contains(key);
                if(actual!=expected)throw new IllegalStateException("Near ownership mask differs from published TLAS/empty section at "+(mx+x)+","+(my+y)+","+(mz+z));
            }
            checks++;
            checkDistantOwnership();
        } catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static long checks(){return checks;}
    public static long finerChecks(){return finerChecks;}
    public static long nestedChecks(){return nestedChecks;}
    @SuppressWarnings("unchecked")
    private static java.util.Map<Long,VoxyLodTransition.Volume> publishedVolumes() throws ReflectiveOperationException {
        var field=RtDistantHorizonsTerrain.class.getDeclaredField("current");field.setAccessible(true);
        Object proxy=field.get(RtDistantHorizonsTerrain.INSTANCE);if(proxy==null)return java.util.Map.of();
        var volumesField=proxy.getClass().getDeclaredField("volumes");volumesField.setAccessible(true);
        return (java.util.Map<Long,VoxyLodTransition.Volume>)volumesField.get(proxy);
    }
    public static void recordBeforeAway() {
        try {beforeAway=publishedVolumes().values().stream().filter(v->v.width()==32).toList();}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        if(beforeAway.isEmpty())throw new IllegalStateException("No fine geometry before leaving");
    }
    public static void requireCoarsened() {
        try {
            var active=publishedVolumes();
            coarsened=beforeAway.stream().filter(f->!active.containsKey(f.key())
                    &&active.values().stream().anyMatch(p->p.width()>=128&&VoxySourceOwnership.contains(p,f))).toList();
            System.out.println("GYSF_RETURN_COARSENING: fineBefore="+beforeAway.size()+" replacedByLargeCoarse="+coarsened.size());
            if(coarsened.isEmpty())throw new IllegalStateException("Route never actually coarsened the previously fine region");
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static void requireRecovered() {
        try {
            var active=publishedVolumes();
            long recovered=coarsened.stream().filter(f->active.containsKey(f.key())).count();
            System.out.println("GYSF_RETURN_RECOVERY: recoveredFine="+recovered+" coarsened="+coarsened.size()+" nestedChecks="+nestedChecks);
            if(recovered==0||nestedChecks==0)throw new IllegalStateException("Missing genuine coarse-to-fine progressive recovery");
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    @SuppressWarnings("unchecked")
    private static void checkDistantOwnership() throws ReflectiveOperationException {
        var field=RtDistantHorizonsTerrain.class.getDeclaredField("current");field.setAccessible(true);
        Object proxy=field.get(RtDistantHorizonsTerrain.INSTANCE);if(proxy==null)return;
        var volumesField=proxy.getClass().getDeclaredField("volumes");volumesField.setAccessible(true);
        var volumes=(java.util.Map<Long,VoxyLodTransition.Volume>)volumesField.get(proxy);
        var ownership=new VoxySourceOwnership(volumes.values());
        var tableField=proxy.getClass().getDeclaredField("table");tableField.setAccessible(true);
        var offsetField=proxy.getClass().getDeclaredField("ownershipOffset");offsetField.setAccessible(true);
        var table=(dev.comfyfluffy.caustica.rt.accel.RtBuffer)tableField.get(proxy);
        var actual=org.lwjgl.system.MemoryUtil.memByteBuffer(table.mapped+offsetField.getLong(proxy),ownership.byteSize());
        var expected=ByteBuffer.allocate(ownership.byteSize());ownership.write(expected);expected.flip();
        if(!actual.equals(expected))throw new IllegalStateException("GPU ownership index differs from published source set");
        var cubes=new java.util.HashSet<Cube>();
        for(var v:volumes.values())cubes.add(new Cube(v.x(),v.y(),v.z(),v.width()));
        for(var fine:volumes.values()) {
            for(int width=fine.width()*2;width<=512;width*=2) {
                if(!ownership.hasFiner(fine.x(),fine.y(),fine.z(),width))
                    throw new IllegalStateException("Complete fine source does not mask ancestor fallback");
                finerChecks++;
                if(cubes.contains(new Cube(Math.floorDiv(fine.x(),width)*width,Math.floorDiv(fine.y(),width)*width,
                        Math.floorDiv(fine.z(),width)*width,width)))nestedChecks++;
            }
        }
    }
    private static Object field(RtTerrain near,String name)throws ReflectiveOperationException {
        var field=RtTerrain.class.getDeclaredField(name);field.setAccessible(true);return field.get(near);
    }
}
