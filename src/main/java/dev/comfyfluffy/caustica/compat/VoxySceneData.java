package dev.comfyfluffy.caustica.compat;

import net.minecraft.world.level.block.state.BlockState;

/** Immutable CPU data copied from optional API 5; no provider types or native resources escape here. */
public final class VoxySceneData {
    private VoxySceneData() {}
    /** Bit 32 preserves authored translucency when the provider optimizes raster alpha to cutout. */
    public static boolean isGlass(int flags,boolean fluid) {return !fluid&&(flags&(4|32))!=0;}

    public record Model(int id, int down, int up, int north, int south, int west, int east,
                        int flags, int tint, BlockState state, boolean fluid, boolean water,
                        boolean glass, int emission) {
        public int face(int face) {
            return switch (face) {
                case 0 -> down; case 1 -> up; case 2 -> north;
                case 3 -> south; case 4 -> west; case 5 -> east;
                default -> throw new IllegalArgumentException("Voxy face outside 0..5");
            };
        }
    }

    public static final class Palette {
        private final Model[] models;
        private final int[] colours;
        public Palette(Model[] models, int[] colours) {
            this.models = models.clone(); this.colours = colours.clone();
        }
        public Model model(int id) {
            if (id < 0 || id >= models.length || models[id] == null)
                throw new IllegalStateException("Voxy mesh references an unpublished model: " + id);
            return models[id];
        }
        public int colour(int index) { return index >= 0 && index < colours.length ? colours[index] : -1; }
    }

    public static final class Mesh {
        private final long[] quads;
        private final long[] fluidHeights;
        private final Palette palette;
        private final int level;
        public Mesh(long[] quads, Palette palette, int level) {
            this(quads,palette,level,new long[0]);
        }
        public Mesh(long[] quads, Palette palette, int level, long[] fluidHeights) {
            if (level < 0 || level > 4) throw new IllegalArgumentException("Voxy LOD outside 0..4");
            if (fluidHeights.length != 0 && fluidHeights.length != quads.length)
                throw new IllegalArgumentException("Voxy fluid heights must match quad order");
            this.quads = quads.clone(); this.palette = palette; this.level = level;
            this.fluidHeights = fluidHeights.clone();
        }
        public int quadCount() { return quads.length; }
        public long quad(int index) { return quads[index]; }
        public long fluidHeights(int index) { return fluidHeights.length == 0 ? 0 : fluidHeights[index]; }
        public Model model(int id) { return palette.model(id); }
        public int colour(int index) { return palette.colour(index); }
        public int level() { return level; }
    }
}
