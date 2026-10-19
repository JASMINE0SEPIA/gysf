package dev.comfyfluffy.caustica.rt.terrain;

import java.util.*;

/** Render-thread octree lookup; queries visit only the requested cube, not the whole view. */
final class VoxySourceLookup {
    private record Cube(int x,int y,int z,int width) {}
    private final Map<Cube,Long> byBounds=new HashMap<>();
    private final Map<Long,Cube> byKey=new HashMap<>();

    void put(VoxyLodTransition.Volume v) {
        if(v.width()<32 || v.width()>512)throw new IllegalArgumentException("Unsupported Voxy cube size");
        Cube cube=new Cube(v.x(),v.y(),v.z(),v.width());
        Long existing=byBounds.get(cube);
        if(existing!=null && existing!=v.key())throw new IllegalArgumentException("Duplicate Voxy bounds");
        remove(v.key());byBounds.put(cube,v.key());byKey.put(v.key(),cube);
    }
    void remove(long key) {
        Cube old=byKey.remove(key);
        if(old!=null)byBounds.remove(old);
    }
    Set<Long> contained(VoxyLodTransition.Volume ready) {
        Set<Long> result=new HashSet<>();
        for(int width=ready.width();width>=32;width/=2)
            for(int z=ready.z();z<ready.z()+ready.width();z+=width)
                for(int y=ready.y();y<ready.y()+ready.width();y+=width)
                    for(int x=ready.x();x<ready.x()+ready.width();x+=width) {
                        Long key=byBounds.get(new Cube(x,y,z,width));
                        if(key!=null)result.add(key);
                    }
        return result;
    }
}
