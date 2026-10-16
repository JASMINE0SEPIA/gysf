package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongConsumer;

/** Streaming nearest-tile selection; CPU request storage remains bounded even in a large LOD scene. */
final class RtWaterFieldRequests implements LongConsumer {
    private final int limit;
    private final int minY,maxY;
    private final double x,y,z;
    private final LongOpenHashSet selected;
    private final LongHeapPriorityQueue farthest;
    RtWaterFieldRequests(int limit,double x,double y,double z,int minY,int maxY) {
        if(limit<1||limit>RtWaterFieldImage.MAX_TILES||minY>=maxY)throw new IllegalArgumentException("Invalid water residency bounds");
        this.limit=limit;this.x=x;this.y=y;this.z=z;
        this.minY=minY;this.maxY=maxY;
        selected=new LongOpenHashSet(limit);farthest=new LongHeapPriorityQueue(limit,(a,b)->compare(b,a));
    }
    private double distance(long key) {
        double dx=RtWaterFieldImage.x(key)*16.0+8-x,dz=RtWaterFieldImage.z(key)*16.0+8-z;
        double dy=RtWaterFieldImage.y(key)+1-y;return dx*dx+dz*dz+dy*dy;
    }
    private int compare(long a,long b) {
        int order=Double.compare(distance(a),distance(b));return order!=0?order:Long.compare(a,b);
    }
    @Override public void accept(long key) {
        // A published fallback can outlive the dimension that produced it for a render boundary.
        // The provider rejects foreign-height layers; never enqueue those into the new world.
        int sourceY=RtWaterFieldImage.y(key);if(sourceY<minY||sourceY>=maxY)return;
        if(selected.contains(key))return;
        if(selected.size()==limit) {
            if(compare(key,farthest.firstLong())>=0)return;
            selected.remove(farthest.dequeueLong());
        }
        selected.add(key);farthest.enqueue(key);
    }
    int size(){return selected.size();}
    int[] triples() {
        long[] keys=selected.toLongArray();it.unimi.dsi.fastutil.longs.LongArrays.quickSort(keys,this::compare);
        int[] result=new int[keys.length*3];
        for(int i=0;i<keys.length;i++) {
            result[i*3]=RtWaterFieldImage.x(keys[i]);result[i*3+1]=RtWaterFieldImage.y(keys[i]);result[i*3+2]=RtWaterFieldImage.z(keys[i]);
        }
        return result;
    }
}
