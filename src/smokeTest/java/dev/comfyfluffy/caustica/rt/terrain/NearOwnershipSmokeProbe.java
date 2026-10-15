package dev.comfyfluffy.caustica.rt.terrain;

import java.nio.*;
import java.lang.reflect.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Check the shader mask against actual published TLAS inputs after rendering, during window changes. */
public final class NearOwnershipSmokeProbe {
    private static long nextCheck,checks;
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
        } catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static long checks(){return checks;}
    private static Object field(RtTerrain near,String name)throws ReflectiveOperationException {
        var field=RtTerrain.class.getDeclaredField(name);field.setAccessible(true);return field.get(near);
    }
}
