package dev.comfyfluffy.caustica.compat;

import java.util.LinkedHashMap;

/** CPU-only LRU scoped to one provider epoch; view selection does not invalidate source identity. */
final class VoxyMeshCache {
    record Entry(long providerRevision, long version, VoxySceneData.Mesh mesh) {}
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(256, .75f, true);
    private final long limit;
    private long bytes, sequence;

    VoxyMeshCache(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative Voxy snapshot cache limit");
        this.limit = limit;
    }

    Entry get(long key, long revision, VoxySceneData.Palette palette) {
        Entry entry = entries.get(key);
        return entry != null && entry.providerRevision == revision && entry.mesh.compatiblePalette(palette) ? entry : null;
    }

    Entry put(long key, long revision, long[] quads, long[] fluids, int level, VoxySceneData.Palette palette) {
        Entry old = entries.get(key);
        Entry next = old != null && old.mesh.sameContent(quads, fluids, level) && old.mesh.compatiblePalette(palette)
                ? new Entry(revision, old.version, old.mesh)
                : new Entry(revision, ++sequence, new VoxySceneData.Mesh(quads, palette, level, fluids));
        if (old != null) bytes -= old.mesh.retainedBytes();
        entries.put(key, next);
        bytes += next.mesh.retainedBytes();
        var iterator = entries.values().iterator();
        while (bytes > limit && iterator.hasNext()) {
            bytes -= iterator.next().mesh.retainedBytes();
            iterator.remove();
        }
        return next;
    }

    long bytes() { return bytes; }
    int size() { return entries.size(); }
    void clear() { entries.clear(); bytes = 0; }
}
