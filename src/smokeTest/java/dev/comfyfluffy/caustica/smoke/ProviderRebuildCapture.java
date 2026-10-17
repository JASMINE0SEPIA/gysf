package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import net.minecraft.client.Minecraft;

/** Rebuild after the client tick, before drawing: the same invalidation window as video settings. */
public final class ProviderRebuildCapture {
    public static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.providerRebuild");
    private static boolean armed,done;
    private static int phase;
    private static long lastChange,checks;
    public static void arm(){armed=true;}
    public static boolean done(){return done;}

    public static void beforeRender() {
        if(!ENABLED||!armed||done)return;
        var client=Minecraft.getInstance();
        if(client.level==null||client.player==null)return;
        if(RtComposite.INSTANCE.hasFailed())throw new IllegalStateException("Provider rebuild compositor failed");
        long now=System.nanoTime();
        if(phase==0&&RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()<16)return;
        if(lastChange!=0&&now-lastChange<20_000_000_000L)return;
        if(phase==4) {
            if(checks<100)throw new IllegalStateException("Insufficient provider boundary checks");
            System.out.println("GYSF_PROVIDER_REBUILD: PASS rebuilds=4 boundaryChecks="+checks
                    +" frames="+RtComposite.frameCounter());
            client.gui.setScreen(null);done=true;return;
        }
        if(phase==0)client.pauseGame(false);
        int distance=phase==1?6:32;
        client.options.renderDistance().set(distance);
        client.options.broadcastOptions();
        // Explicitly exercise F3+A too. This runs after START_CLIENT_TICK provider polling.
        client.levelExtractor.allChanged();
        System.out.println("GYSF_PROVIDER_REBUILD: rebuilt phase="+phase+" near="+distance
                +" paused="+client.isPaused()+" previousEpoch="+VoxyCompat.sceneEpoch());
        phase++;lastChange=now;
    }

    /** Inspect only; never poll the provider or repair a stale consumer during the assertion. */
    public static void checkBoundary() {
        if(!ENABLED||VoxyCompat.sceneEpoch()==Long.MIN_VALUE)return;
        try {
            Object renderer=Class.forName("me.cortex.voxy.client.core.IVoxyRenderSystemHolder")
                    .getMethod("getNullable").invoke(null);
            if(renderer==null)return;
            Object core=renderer.getClass().getField("vkCore").get(renderer);
            if(core==null)return;
            var sessionField=core.getClass().getDeclaredField("externalSession");sessionField.setAccessible(true);
            Object session=sessionField.get(core);
            long actual=Long.MIN_VALUE;
            if(session!=null) {
                var epochField=session.getClass().getDeclaredField("epoch");epochField.setAccessible(true);
                actual=epochField.getLong(session);
            }
            if(actual!=VoxyCompat.sceneEpoch())throw new IllegalStateException(
                    "GYSF_STALE_PROVIDER: cachedEpoch="+VoxyCompat.sceneEpoch()+" actualEpoch="+actual);
            checks++;
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
}
