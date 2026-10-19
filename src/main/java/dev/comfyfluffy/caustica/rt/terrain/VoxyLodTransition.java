package dev.comfyfluffy.caustica.rt.terrain;

import java.util.*;

/** Branch-local, atomic handoff between two non-overlapping octree selections. No GPU resources owned. */
final class VoxyLodTransition {
    record Volume(long key, int x, int y, int z, int width) {
        Volume {
            if (width <= 0 || (width & (width - 1)) != 0
                    || Math.floorMod(x, width) != 0 || Math.floorMod(y, width) != 0
                    || Math.floorMod(z, width) != 0)
                throw new IllegalArgumentException("Voxy volume must be an aligned power-of-two cube");
        }
        Bounds bounds() { return new Bounds(x, y, z, width); }
    }
    private record Bounds(int x, int y, int z, int width) {
        @Override public int hashCode(){return VoxySourceOwnership.hash(x,y,z,width);}
        Bounds parent() {
            int w = Math.multiplyExact(width, 2);
            return new Bounds(Math.floorDiv(x, w) * w, Math.floorDiv(y, w) * w,
                    Math.floorDiv(z, w) * w, w);
        }
    }
    record Swap(Set<Long> retired, Set<Long> activated) {}
    private static final class Group {
        final Set<Long> old = new HashSet<>(), next = new HashSet<>();
        int remaining;
    }
    private final Map<Long, Group> groups = new HashMap<>();
    private final Map<Long, Integer> pendingBatches = new HashMap<>();
    private final Set<Long> obsolete = new HashSet<>();

    VoxyLodTransition(List<Volume> previous, List<Volume> desired) {
        this(previous, desired, Map.of());
    }

    VoxyLodTransition(List<Volume> previous, List<Volume> desired, Map<Long, Integer> batchCounts) {
        this(previous, desired, batchCounts, false);
    }

    VoxyLodTransition(List<Volume> previous, List<Volume> desired, Map<Long, Integer> batchCounts,
                      boolean nestedFallback) {
        int n = previous.size(), total = n + desired.size();
        int[] parent = new int[total];
        for (int i = 0; i < total; i++) parent[i] = i;
        Map<Bounds, Integer> oldIndex = index(previous, nestedFallback), nextIndex = index(desired);
        int maxWidth = 1;
        for (Volume v : previous) maxWidth = Math.max(maxWidth, v.width);
        for (Volume v : desired) maxWidth = Math.max(maxWidth, v.width);
        // Aligned octree cubes either nest or do not intersect. Ancestor lookups avoid an O(N^2)
        // render-thread scan and preserve the Y coordinate, including negative world heights.
        connect(previous, nextIndex, parent, 0, n, maxWidth);
        connect(desired, oldIndex, parent, n, 0, maxWidth);
        Map<Integer, Group> byRoot = new HashMap<>();
        for (int i = 0; i < n; i++)
            byRoot.computeIfAbsent(root(parent, i), k -> new Group()).old.add(previous.get(i).key);
        for (int i = 0; i < desired.size(); i++) {
            Group g = byRoot.computeIfAbsent(root(parent, n + i), k -> new Group());
            g.next.add(desired.get(i).key);
            groups.put(desired.get(i).key, g);
            int batches = batchCounts.getOrDefault(desired.get(i).key, 1);
            if (batches < 1) throw new IllegalArgumentException("A Voxy source needs at least an empty marker");
            pendingBatches.put(desired.get(i).key, batches);
            g.remaining += batches;
        }
        for (Group g : byRoot.values()) {
            if (g.remaining == 0) obsolete.addAll(g.old);
        }
    }

    Set<Long> obsoleteSources() { return Set.copyOf(obsolete); }
    static void validatePartition(List<Volume> volumes) { index(volumes); }

    /** Empty markers count as one batch; no source or overlapping branch may publish partially. */
    Swap completeBatch(long key) {
        Group g = groups.get(key);
        if (g == null) throw new IllegalArgumentException("Unplanned Voxy source " + key);
        int pending = pendingBatches.get(key);
        if (pending == 0) return null;
        pendingBatches.put(key, pending - 1);
        if (--g.remaining != 0) return null;
        return new Swap(Set.copyOf(g.old), Set.copyOf(g.next));
    }

    private static Map<Bounds, Integer> index(List<Volume> volumes) {
        return index(volumes, false);
    }

    private static Map<Bounds, Integer> index(List<Volume> volumes, boolean allowNested) {
        Map<Bounds, Integer> result = new HashMap<>();
        Set<Long> keys = new HashSet<>();
        int maxWidth = volumes.stream().mapToInt(Volume::width).max().orElse(1);
        for (int i = 0; i < volumes.size(); i++) {
            Volume v = volumes.get(i);
            if (!keys.add(v.key) || result.put(v.bounds(), i) != null)
                throw new IllegalArgumentException("Duplicate Voxy source");
        }
        for (Volume v : allowNested ? List.<Volume>of() : volumes) {
            for (Bounds b = v.bounds(); b.width < maxWidth;) {
                b = b.parent();
                if (result.containsKey(b)) throw new IllegalArgumentException("Overlapping Voxy selection");
            }
        }
        return result;
    }

    private static void connect(List<Volume> from, Map<Bounds, Integer> to, int[] parents,
                                int fromOffset, int toOffset, int maxWidth) {
        for (int i = 0; i < from.size(); i++) {
            for (Bounds b = from.get(i).bounds();;) {
                Integer other = to.get(b);
                if (other != null) parents[root(parents, fromOffset + i)] = root(parents, toOffset + other);
                if (b.width >= maxWidth) break;
                b = b.parent();
            }
        }
    }
    private static int root(int[] parents, int i) {
        while (parents[i] != i) { parents[i] = parents[parents[i]]; i = parents[i]; }
        return i;
    }
}
