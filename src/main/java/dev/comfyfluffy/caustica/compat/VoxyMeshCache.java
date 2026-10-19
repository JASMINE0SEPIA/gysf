package dev.comfyfluffy.caustica.compat;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/** CPU-only LRU scoped to one provider epoch; view selection does not invalidate source identity. */
final class VoxyMeshCache {
    record Entry(long providerRevision, long version, VoxySceneData.Mesh mesh) {}
    private final Long2ObjectLinkedOpenHashMap<Entry> entries = new Long2ObjectLinkedOpenHashMap<>(256);
    private final long limit;
    private long bytes, sequence;

    VoxyMeshCache(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative Voxy snapshot cache limit");
        this.limit = limit;
    }

    Entry get(long key, long revision, VoxySceneData.Palette palette) {
        Entry entry = entries.getAndMoveToLast(key);
        return entry != null && entry.providerRevision == revision && entry.mesh.compatiblePalette(palette) ? entry : null;
    }

    Entry put(long key, long revision, long[] quads, long[] fluids, int level, VoxySceneData.Palette palette) {
        Entry old = entries.getAndMoveToLast(key);
        Entry next = old != null && old.mesh.sameContent(quads, fluids, level) && old.mesh.compatiblePalette(palette)
                ? new Entry(revision, old.version, old.mesh)
                : new Entry(revision, ++sequence, new VoxySceneData.Mesh(quads, palette, level, fluids));
        if (old != null) bytes -= old.mesh.retainedBytes();
        entries.putAndMoveToLast(key, next);
        bytes += next.mesh.retainedBytes();
        while (bytes > limit && !entries.isEmpty()) bytes -= entries.removeFirst().mesh.retainedBytes();
        return next;
    }

    long bytes() { return bytes; }
    int size() { return entries.size(); }
    void clear() { entries.clear(); bytes = 0; }
}
