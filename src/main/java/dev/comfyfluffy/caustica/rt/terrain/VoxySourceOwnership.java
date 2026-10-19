package dev.comfyfluffy.caustica.rt.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Immutable GPU ownership index. Complete finer sources, including empty ones, clip coarse fallback. */
final class VoxySourceOwnership {
    static final int MAGIC = 0x56584f57; // VXOW
    private final int[] slots;
    private final int mask;
    private final List<VoxyLodTransition.Volume> volumes;

    VoxySourceOwnership(Collection<VoxyLodTransition.Volume> input) {
        // Canonical insertion order makes the immutable upload reproducible across Map snapshots.
        volumes = input.stream().sorted(Comparator.comparingLong(VoxyLodTransition.Volume::key)).toList();
        int capacity = 1;
        while (capacity < Math.multiplyExact(volumes.size(), 2)) capacity = Math.multiplyExact(capacity, 2);
        mask = capacity - 1;
        slots = new int[Math.multiplyExact(capacity, 4)];
        Set<Long> keys = new HashSet<>();
        for (var v : volumes) {
            if (v.width() < 32 || v.width() > 512 || !keys.add(v.key()))
                throw new IllegalArgumentException("Invalid or duplicate Voxy ownership source");
            int slot = hash(v.x(), v.y(), v.z(), v.width()) & mask;
            while (slots[slot * 4 + 3] != 0) {
                if (matches(slot, v.x(), v.y(), v.z(), v.width()))
                    throw new IllegalArgumentException("Duplicate Voxy ownership bounds");
                slot = (slot + 1) & mask;
            }
            int p = slot * 4;
            slots[p] = v.x(); slots[p + 1] = v.y(); slots[p + 2] = v.z(); slots[p + 3] = v.width();
        }
    }

    static boolean contains(VoxyLodTransition.Volume outer, VoxyLodTransition.Volume inner) {
        return outer.width() >= inner.width()
                && Math.floorDiv(inner.x(), outer.width()) * outer.width() == outer.x()
                && Math.floorDiv(inner.y(), outer.width()) * outer.width() == outer.y()
                && Math.floorDiv(inner.z(), outer.width()) * outer.width() == outer.z();
    }

    static int distanceTo(VoxyLodTransition.Volume volume, int x, int y, int z) {
        int dx=Math.max(0,Math.max(volume.x()-x,x-volume.x()-volume.width()));
        int dy=Math.max(0,Math.max(volume.y()-y,y-volume.y()-volume.width()));
        int dz=Math.max(0,Math.max(volume.z()-z,z-volume.z()-volume.width()));
        return Math.max(dx,Math.max(dy,dz));
    }

    /** Call only after every batch of a source is ready. Keep ancestors as spatially clipped fallback. */
    static Set<Long> activate(Map<Long, VoxyLodTransition.Volume> published, VoxyLodTransition.Volume ready) {
        Set<Long> retired = new HashSet<>();
        published.forEach((key, old) -> { if (contains(ready, old)) retired.add(key); });
        retired.forEach(published::remove);
        published.put(ready.key(), ready);
        return retired;
    }

    boolean hasFiner(int x, int y, int z, int sourceWidth) {
        for (int w = 32; w < sourceWidth; w *= 2) {
            int ox = Math.floorDiv(x, w) * w, oy = Math.floorDiv(y, w) * w, oz = Math.floorDiv(z, w) * w;
            int slot = hash(ox, oy, oz, w) & mask;
            while (slots[slot * 4 + 3] != 0) {
                if (matches(slot, ox, oy, oz, w)) return true;
                slot = (slot + 1) & mask;
            }
        }
        return false;
    }

    boolean hasCoarser(VoxyLodTransition.Volume volume) {
        for(int width=volume.width()*2;width<=512;width*=2) {
            int x=Math.floorDiv(volume.x(),width)*width,y=Math.floorDiv(volume.y(),width)*width,
                    z=Math.floorDiv(volume.z(),width)*width;
            int slot=hash(x,y,z,width)&mask;
            while(slots[slot*4+3]!=0) {
                if(matches(slot,x,y,z,width))return true;
                slot=(slot+1)&mask;
            }
        }
        return false;
    }

    private boolean matches(int slot, int x, int y, int z, int w) {
        int p = slot * 4;
        return slots[p] == x && slots[p + 1] == y && slots[p + 2] == z && slots[p + 3] == w;
    }

    // Keep identical to voxyFinerSourceReady in world.rahit.slang, including unsigned overflow.
    static int hash(int x, int y, int z, int width) {
        int h = x * 0x8da6b343 ^ y * 0xd8163841 ^ z * 0xcb1ab31f ^ width * 0x9e3779b9;
        h ^= h >>> 16; h *= 0x7feb352d; h ^= h >>> 15;
        return h;
    }

    int byteSize() { return 16 + slots.length * 4; }
    void write(ByteBuffer target) {
        target.order(ByteOrder.LITTLE_ENDIAN);
        target.putInt(mask).putInt(MAGIC).putInt(volumes.size()).putInt(0);
        for (int value : slots) target.putInt(value);
    }
}
