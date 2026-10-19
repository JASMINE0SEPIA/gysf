package dev.comfyfluffy.caustica.compat;

import net.minecraft.world.level.block.state.BlockState;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

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
        private final int[] usedModels, usedColours;
        public Mesh(long[] quads, Palette palette, int level) {
            this(quads,palette,level,new long[0]);
        }
        public Mesh(long[] quads, Palette palette, int level, long[] fluidHeights) {
            if (level < 0 || level > 4) throw new IllegalArgumentException("Voxy LOD outside 0..4");
            if (fluidHeights.length != 0 && fluidHeights.length != quads.length)
                throw new IllegalArgumentException("Voxy fluid heights must match quad order");
            this.quads = quads.clone(); this.palette = palette; this.level = level;
            this.fluidHeights = fluidHeights.clone();
            BitSet modelIds = new BitSet(), colourIds = new BitSet();
            for (long quad : quads) {
                int id = (int)(quad >>> 26) & 65535;
                modelIds.set(id);
                Model model = palette.model(id);
                if ((model.flags() & 2) != 0 && model.tint() >= 0)
                    colourIds.set(model.tint() + ((int)(quad >>> 46) & 511));
            }
            usedModels = modelIds.stream().toArray();
            usedColours = colourIds.stream().toArray();
        }
        public int quadCount() { return quads.length; }
        public long quad(int index) { return quads[index]; }
        public long fluidHeights(int index) { return fluidHeights.length == 0 ? 0 : fluidHeights[index]; }
        public Model model(int id) { return palette.model(id); }
        public int colour(int index) { return palette.colour(index); }
        public int level() { return level; }
        long retainedBytes() { return 96L + 8L * (quads.length + fluidHeights.length) + 4L * (usedModels.length + usedColours.length); }
        boolean sameContent(long[] packed, long[] fluids, int lod) {
            return level == lod && Arrays.equals(quads, packed) && Arrays.equals(fluidHeights, fluids);
        }
        boolean compatiblePalette(Palette next) {
            if (palette == next) return true;
            for (int id : usedModels) if (!Objects.equals(palette.models[id], next.models[id])) return false;
            for (int id : usedColours) if (palette.colour(id) != next.colour(id)) return false;
            return true;
        }
    }
}
