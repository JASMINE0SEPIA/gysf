package dev.comfyfluffy.caustica.rt.terrain;

import java.util.*;

/** Bounded ownership transfer for complete fine sources, admitted only after their last GPU use. */
final class VoxyDetailCache<T> {
    record Entry<T>(VoxyLodTransition.Volume volume,long revision,List<T> batches,long bytes) {
        Entry { batches=List.copyOf(batches);if(bytes<0)throw new IllegalArgumentException("Negative cache size"); }
    }
    private final LinkedHashMap<Long,Entry<T>> entries=new LinkedHashMap<>();
    private long bytes;
    synchronized long bytes(){return bytes;}
    synchronized int size(){return entries.size();}
    synchronized Entry<T> take(long key) {
        var entry=entries.remove(key);if(entry!=null)bytes-=entry.bytes();return entry;
    }
    synchronized Entry<T> takeMatching(long key,long revision) {
        var entry=entries.get(key);
        return entry!=null&&entry.revision()==revision ? take(key) : null;
    }
    synchronized List<Entry<T>> put(Entry<T> entry,long limit) {
        if(limit<0)throw new IllegalArgumentException("Negative cache limit");
        List<Entry<T>> evicted=new ArrayList<>();
        var existing=entries.get(entry.volume().key());
        if(existing!=null&&existing.revision()>=entry.revision())evicted.add(entry);
        else {
            var old=take(entry.volume().key());if(old!=null)evicted.add(old);
            entries.put(entry.volume().key(),entry);bytes=Math.addExact(bytes,entry.bytes());
        }
        var iterator=entries.values().iterator();
        while(bytes>limit&&iterator.hasNext()) {
            var victim=iterator.next();iterator.remove();bytes-=victim.bytes();evicted.add(victim);
        }
        return evicted;
    }
    synchronized List<Entry<T>> clear() {
        var result=List.copyOf(entries.values());entries.clear();bytes=0;return result;
    }
}
