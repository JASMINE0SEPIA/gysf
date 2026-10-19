package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

/** Fixed-camera persistence replay on a complete physical Greenfield copy. */
public final class LodReentryCapture {
    private static volatile int cameraTicks;
    private static final boolean TURN = Boolean.getBoolean("gysf.smoke.reentry.turn");
    public static boolean locked() { return Boolean.getBoolean("gysf.smoke.reentry"); }
    public static void enforceCamera() {
        if (!locked()) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        float yaw = TURN && cameraTicks >= 200 && cameraTicks < 600
                ? 35 + 90 * (((cameraTicks - 200) / 40 + 1) % 4) : 35;
        player.setYRot(yaw); player.yRotO = yaw;
        player.setXRot(15); player.xRotO = 15;
        player.setYHeadRot(yaw); player.yHeadRotO = yaw;
    }
    private static final boolean BASELINE = Boolean.getBoolean("gysf.smoke.reentry.baseline");
    private static final boolean COLD = Boolean.getBoolean("gysf.smoke.reentry.cold");
    private static final int HIGH = Integer.getInteger("gysf.smoke.reentry.highDistance", 16);
    private static final int LOW = 6;
    private int ticks, worldTicks, phase, exitTicks, stableTicks;
    private boolean opened, finished;
    private long beforeEpoch;
    private long worldStartedNanos;
    private int residentBeforeTurn;
    private final Map<String, Object> results = new LinkedHashMap<>();

    void tick(Minecraft client) {
        if (++ticks > 16000) throw new IllegalStateException("LOD reentry replay timed out");
        if (finished) { if (++exitTicks > 40) client.stop(); return; }
        if (!opened && client.gui.screen() instanceof TitleScreen && ticks > 50) {
            Path world = Path.of("saves", "gysf-reentry").toAbsolutePath().normalize();
            if (!world.startsWith(Path.of(".").toAbsolutePath().normalize()) || !Files.exists(world.resolve("level.dat")))
                throw new IllegalStateException("LOD replay requires an isolated save copy");
            opened = true;
            client.options.pauseOnLostFocus = false;
            client.options.enableVsync().set(false);
            client.options.renderDistance().set(COLD || phase == 1 ? LOW : HIGH);
            client.options.simulationDistance().set(6);
            client.options.framerateLimit().set(60);
            client.options.fov().set(70);
            client.getWindow().setWindowed(1600, 1000);
            client.createWorldOpenFlows().openWorld("gysf-reentry", () -> {
                throw new IllegalStateException("LOD replay cancelled");
            });
            worldTicks = 0; stableTicks = 0; cameraTicks = 0; worldStartedNanos = 0;
        }
        if (client.level == null || client.player == null) return;
        if (RtComposite.INSTANCE.hasFailed()) throw new IllegalStateException("RT failed during LOD reentry");
        ++worldTicks;
        cameraTicks = worldTicks;
        if (worldStartedNanos == 0) worldStartedNanos = System.nanoTime();
        if (TURN && worldTicks == 200) residentBeforeTurn = RtDistantHorizonsTerrain.INSTANCE.residentSectionCount();
        if (TURN && worldTicks == 600 && !BASELINE) {
            var stats = RtDistantHorizonsTerrain.INSTANCE.streamingStats();
            if (stats.get("viewCancellations") != 0 || stats.get("retargets") < 2
                    || RtDistantHorizonsTerrain.INSTANCE.residentSectionCount() <= residentBeforeTurn)
                throw new IllegalStateException("Camera turns cancelled or starved LOD progress: " + stats);
            System.out.println("GYSF_LOD_TURN: PASS " + stats);
        }
        if (worldTicks == 60) {
            client.player.connection.sendCommand("gamemode spectator");
            client.player.connection.sendCommand("time set 6000");
            client.player.connection.sendCommand("weather clear 1000000");
            client.player.connection.sendCommand("gamerule minecraft:advance_time false");
            client.player.connection.sendCommand("tp @s 178.156 130 -284.910 35 15");
        }
        if (worldTicks > 100 && worldTicks % 200 == 0) state(client, "progress-" + phase + "-" + worldTicks);
        if (COLD || phase == 1) {
            if (worldTicks == 200) capture(client, COLD ? "cold-10s" : "reentry-10s");
            if (worldTicks < 900) return;
            if (Boolean.getBoolean("gysf.smoke.reentry.awaitCompletion")) {
                boolean pending = RtDistantHorizonsTerrain.INSTANCE.hasPendingBuilds() || VoxyCompat.refinementPending();
                stableTicks = pending ? 0 : stableTicks + 1;
                if (stableTicks < 100) {
                    if (worldTicks >= 10000) throw new IllegalStateException("Historical LOD rebuild never completed");
                    return;
                }
            }
            String label = COLD ? "cold-restored" : "reentry-restored";
            state(client, label); capture(client, label);
            int restoredFar = farSources(client), resident = RtDistantHorizonsTerrain.INSTANCE.residentSectionCount();
            int restoredCoverage = coveredFarSections(client);
            if (!BASELINE && (restoredFar < 3 || resident < 3 || restoredCoverage < 10))
                throw new IllegalStateException("Disk LOD did not restore: far=" + restoredFar + " resident=" + resident
                        + " publishedFar=" + restoredCoverage);
            if (!BASELINE && !COLD && beforeEpoch == VoxyCompat.sceneEpoch())
                throw new IllegalStateException("Reentry reused retired scene epoch");
            System.out.println("GYSF_LOD_REENTRY: " + (BASELINE ? "BASELINE" : "PASS")
                    + " cold=" + COLD + " far=" + restoredFar + " resident=" + resident
                    + " publishedFar=" + restoredCoverage + " ingest=" + VoxyCompat.ingestEnabled()
                    + " frames=" + RtComposite.frameCounter());
            finish(client);
            return;
        }
        if (worldTicks == 900) {
            state(client, "high-distance"); capture(client, "high-distance");
            client.options.renderDistance().set(LOW);
            client.options.broadcastOptions();
        }
        if (worldTicks == 1500) {
            state(client, "low-before-exit"); capture(client, "low-before-exit");
            beforeEpoch = VoxyCompat.sceneEpoch();
            // The second session may use only existing Voxy data, never recapture far terrain.
            VoxyCompat.setIngestEnabled(false);
            phase = 1; opened = false;
            client.disconnectWithSavingScreen();
            client.gui.setScreen(new TitleScreen());
        }
    }

    private static int farSources(Minecraft client) {
        int count = 0;
        for (var mesh : VoxyCompat.meshes()) {
            if (mesh.textured() == null || mesh.textured().quadCount() == 0) continue;
            double x = mesh.originX(), z = mesh.originZ(), width = mesh.width();
            double dx = Math.max(Math.max(x - client.player.getX(), client.player.getX() - x - width), 0);
            double dz = Math.max(Math.max(z - client.player.getZ(), client.player.getZ() - z - width), 0);
            if (dx * dx + dz * dz > Math.pow(LOW * 16 + 32, 2)) count++;
        }
        return count;
    }

    private static int coveredFarSections(Minecraft client) {
        int count = 0, cx = Math.floorDiv((int) client.player.getX(), 16), cz = Math.floorDiv((int) client.player.getZ(), 16);
        for (int z = cz - 32; z <= cz + 32; z += 2) for (int x = cx - 32; x <= cx + 32; x += 2) {
            if (Math.hypot(x - cx, z - cz) <= LOW + 2) continue;
            for (int y = 4; y <= 8; y++) if (RtDistantHorizonsTerrain.INSTANCE.hasPublishedCoverage(x, y, z)) count++;
        }
        return count;
    }

    private void state(Minecraft client, String label) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("near", client.options.renderDistance().get());
        values.put("camera", List.of(client.player.getX(), client.player.getY(), client.player.getZ()));
        values.put("epoch", VoxyCompat.sceneEpoch());
        values.put("sources", VoxyCompat.texturedMeshCount());
        values.put("far", farSources(client));
        values.put("resident", RtDistantHorizonsTerrain.INSTANCE.residentSectionCount());
        values.put("publishedFar", coveredFarSections(client));
        values.put("pendingBuilds", RtDistantHorizonsTerrain.INSTANCE.hasPendingBuilds());
        values.put("temporaryDistance", RtDistantHorizonsTerrain.INSTANCE.memoryDistanceLimit());
        values.put("ingest", VoxyCompat.ingestEnabled());
        values.put("elapsedSeconds", (System.nanoTime() - worldStartedNanos) / 1e9);
        values.put("streaming", RtDistantHorizonsTerrain.INSTANCE.streamingStats());
        values.put("snapshotCache", VoxyCompat.snapshotCacheStats());
        try {
            values.put("backend", Class.forName("me.cortex.voxy.client.core.backend.VoxyGraphicsBackend")
                    .getMethod("statusLine").invoke(null));
            Object renderer = Class.forName("me.cortex.voxy.client.core.IVoxyRenderSystemHolder")
                    .getMethod("getNullable").invoke(null);
            values.put("renderer", renderer != null);
            values.put("legacy", Class.forName("me.cortex.voxy.client.core.backend.blaze3d.VoxyBlaze3DProbeRenderer")
                    .getMethod("getLodDebugSummary").invoke(null));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        results.put(label, values);
        System.out.println("GYSF_LOD_REENTRY: " + label + " " + values);
    }

    private void finish(Minecraft client) {
        try {
            Files.writeString(Path.of(COLD ? "reentry-cold.json" : BASELINE ? "reentry-baseline.json" : "reentry-fixed.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results));
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        client.disconnectWithSavingScreen(); finished = true;
    }

    private static void capture(Minecraft client, String label) {
        Path folder = Path.of("reentry-captures");
        try { Files.createDirectories(folder); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try { image.writeToFile(folder.resolve(label + ".png")); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            finally { image.close(); }
        });
    }
}
