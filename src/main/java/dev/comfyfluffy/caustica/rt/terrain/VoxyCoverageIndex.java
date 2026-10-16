package dev.comfyfluffy.caustica.rt.terrain;

import java.util.*;

/** Spatial membership of complete, currently published Voxy source volumes. No GPU owners retained. */
final class VoxyCoverageIndex {
    private record Cube(int x,int y,int z,int width) {}
    private final Set<Cube> cubes;
    private final int maximum;
    VoxyCoverageIndex(Collection<VoxyLodTransition.Volume> volumes) {
        Set<Cube> result=new HashSet<>();int max=0;
        for(var volume:volumes) {
            result.add(new Cube(volume.x(),volume.y(),volume.z(),volume.width()));
            max=Math.max(max,volume.width());
        }
        cubes=Set.copyOf(result);maximum=max;
    }
    boolean containsSection(int sx,int sy,int sz) {
        int x=Math.multiplyExact(sx,16),y=Math.multiplyExact(sy,16),z=Math.multiplyExact(sz,16);
        for(int width=32;width<=maximum;width*=2) {
            if(cubes.contains(new Cube(Math.floorDiv(x,width)*width,Math.floorDiv(y,width)*width,
                    Math.floorDiv(z,width)*width,width)))return true;
        }
        return false;
    }
}
