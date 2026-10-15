package dev.comfyfluffy.caustica.rt.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;

/** Live chunk ingestion -> provider mesher at every mip -> production RT vertex conversion. */
public final class FluidMipSmokeProbe {
    private static final int X = 64, Y = 176, Z = 48;
    private FluidMipSmokeProbe() {}

    public static void place(MinecraftServer server) {
        server.tickRateManager().setFrozen(true);
        var level = server.overworld();
        var pos = new BlockPos.MutableBlockPos();
        for (int x = 32; x <= 127; x++) for (int z = 0; z <= 95; z++) for (int y = 159; y <= 207; y++) {
            var state = y == 159 || (y <= Y && (x == 32 || x == 127 || z == 0 || z == 95))
                    ? Blocks.QUARTZ_BLOCK.defaultBlockState()
                    : y <= Y ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            level.setBlock(pos.set(x, y, z), state, 2);
        }
        System.out.println("GYSF_FLUID_MIP: placed frozen 96x96 pool; source top=" + Y);
    }

    public static void verify(Minecraft client) {
        try {
            var renderer = Class.forName("me.cortex.voxy.client.core.IVoxyRenderSystemHolder")
                    .getMethod("getNullable").invoke(null);
            if (renderer == null) throw new IllegalStateException("No provider renderer");
            var core = renderer.getClass().getField("vkCore").get(renderer);
            var world = core.getClass().getMethod("getEngine").invoke(core);
            var bakery = field(core, "modelService");
            var models = bakery.getClass().getField("factory").get(bakery);
            Class<?> worldType = world.getClass(), modelType = models.getClass();
            Class<?> sectionType = Class.forName("me.cortex.voxy.common.world.WorldSection");
            Class<?> factoryType = Class.forName("me.cortex.voxy.client.core.rendering.building.RenderDataFactory");
            var constructor = factoryType.getConstructor(worldType, modelType, boolean.class, boolean.class,
                    boolean.class, int.class, int.class);
            float worst = 0;
            for (int level = 0; level <= 4; level++) {
                int scale = 1 << level, width = 32 * scale;
                int sx = Math.floorDiv(X, width), sy = Math.floorDiv(Y, width), sz = Math.floorDiv(Z, width);
                Object section = worldType.getMethod("acquire", int.class, int.class, int.class, int.class)
                        .invoke(world, level, sx, sy, sz);
                Object factory = constructor.newInstance(world, models, false, false, true,
                        client.level.getMinY(), client.level.getHeight());
                Object built = null;
                try {
                    built = factoryType.getMethod("generateMesh", sectionType).invoke(factory, section);
                    var buffer = built.getClass().getField("geometryBuffer").get(built);
                    if (buffer == null) throw new IllegalStateException("Empty fixture mip " + level);
                    long address = buffer.getClass().getField("address").getLong(buffer);
                    int count = Math.toIntExact(buffer.getClass().getField("size").getLong(buffer) / 8);
                    long[] heights = (long[]) built.getClass().getField("fluidHeights").get(built);
                    var quads = MemoryUtil.memLongBuffer(address, count);
                    boolean found = false;
                    for (int i = 0; i < count; i++) {
                        long raw = quads.get(i);
                        if ((raw & 7) != 1 || ((raw >>> 21) & 31) != Math.floorMod(X / scale, 32)
                                || ((raw >>> 16) & 31) != Math.floorMod(Y / scale, 32)
                                || ((raw >>> 11) & 31) != Math.floorMod(Z / scale, 32)) continue;
                        if (heights.length != count || heights[i] == 0)
                            throw new IllegalStateException("Missing precise fixture geometry at mip " + level);
                        float[] p = new float[12], uv = new float[8];
                        RtVoxyMesh.corners(raw, 0, true, scale, p, uv, heights[i]);
                        float error = 0, expected = Y + 8f / 9f;
                        for (int j = 0; j < 4; j++) error = Math.max(error, Math.abs(p[j * 3 + 1] + sy * width - expected));
                        worst = Math.max(worst, error);
                        System.out.println("GYSF_FLUID_MIP: level=" + level + " expectedY=" + expected
                                + " vertexY=" + (p[1] + sy * width) + " maxErrorBlocks=" + error);
                        if (error > .0003f) throw new IllegalStateException("Live provider water level changed with mip " + level);
                        found = true;
                        break;
                    }
                    if (!found) throw new IllegalStateException("Fixture top face missing at mip " + level);
                } finally {
                    if (built != null) built.getClass().getMethod("free").invoke(built);
                    factoryType.getMethod("free").invoke(factory);
                    sectionType.getMethod("release").invoke(section);
                }
            }
            System.out.println("GYSF_FLUID_MIP: PASS levels=5 worstWorldError=" + worst);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Provider mip fixture failed", e); }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
