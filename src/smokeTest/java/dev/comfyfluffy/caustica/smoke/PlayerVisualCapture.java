package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.rt.RtComposite;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import java.nio.file.*;
import java.util.*;

/** Replays the reported cameras against a copied save. Never opens a player's PCL directory. */
final class PlayerVisualCapture {
    private static final boolean LIFECYCLE=Boolean.getBoolean("gysf.smoke.playerVisualLifecycle");
    private int ticks, worldTicks, exitTicks;
    private boolean opened, finished;
    private int maxHeld;
    private boolean sawNether,returned;
    private java.util.concurrent.CompletableFuture<Void> reload;
    void tick(Minecraft client) {
        if (++ticks > 16000) throw new IllegalStateException("Player visual replay timed out");
        if (finished) { if (++exitTicks > 40) client.stop(); return; }
        if (!opened && client.gui.screen() instanceof TitleScreen && ticks > 40) {
            Path world=Path.of("saves", "gysf-player-visual").toAbsolutePath().normalize();
            if (!world.startsWith(Path.of(".").toAbsolutePath().normalize()) || !Files.exists(world.resolve("level.dat")))
                throw new IllegalStateException("Replay requires an existing isolated world copy");
            opened=true;
            client.options.pauseOnLostFocus=false;
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(100);
            client.options.renderDistance().set(Integer.getInteger("gysf.smoke.playerVisualStartDistance",6));
            client.options.simulationDistance().set(24);
            client.options.cloudStatus().set(CloudStatus.OFF);
            client.options.fov().set(70);
            client.getWindow().setWindowed(1792,1120);
            client.createWorldOpenFlows().openWorld("gysf-player-visual",()->{throw new IllegalStateException("Replay cancelled");});
        }
        if(client.level==null || client.player==null)return;
        ++worldTicks;
        if(client.level.dimension().equals(net.minecraft.world.level.Level.NETHER))sawNether=true;
        if(sawNether && client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD))returned=true;
        if(RtComposite.INSTANCE.hasFailed())throw new IllegalStateException("RT compositor failed");
        var terrain=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull();
        if(terrain!=null)maxHeld=Math.max(maxHeld,terrain.retainedHandoffSectionCount());
        switch(worldTicks) {
            case 80 -> {
                client.player.connection.sendCommand("gamemode spectator");
                client.player.connection.sendCommand("time set 2927");
                client.player.connection.sendCommand("gamerule minecraft:advance_time false");
                teleport(client,"176.507 209.9076 216.777 176.9 24");
            }
            case 1000 -> capture(client,"01-clouds-off");
            case 1010 -> client.options.cloudStatus().set(CloudStatus.FAST);
            case 1150 -> capture(client,"02-clouds-fast");
            case 1160 -> client.options.cloudStatus().set(CloudStatus.FANCY);
            case 1300 -> capture(client,"03-clouds-fancy");
            case 1310 -> {
                client.options.cloudStatus().set(CloudStatus.OFF);
                client.options.renderDistance().set(32);
                client.options.broadcastOptions();
            }
            case 2500 -> capture(client,"04-near32-original-camera");
            case 2510 -> teleport(client,"10.245 209.9076 307.770 -157 42.8");
            case 2900 -> capture(client,"05-moved");
            case 2910 -> teleport(client,"-197.631 244.77137 231.247 -87.5 49.9");
            case 3300 -> capture(client,"06-moved-negative");
            case 3310 -> teleport(client,"176.507 209.9076 216.777 176.9 24");
            case 3800 -> capture(client,"07-returned");
            case 3840 -> {if(!LIFECYCLE)finish(client);}
            case 3900 -> {if(LIFECYCLE)reload=client.reloadResourcePacks();}
            case 4300 -> {
                if(LIFECYCLE) {
                    if(reload==null||!reload.isDone())throw new IllegalStateException("Resource reload did not finish");
                    reload.join();
                    client.player.connection.sendCommand("execute in minecraft:the_nether run tp @s 0 90 0");
                }
            }
            case 4600 -> {if(LIFECYCLE)client.player.connection.sendCommand("execute in minecraft:overworld run tp @s 176.507 209.9076 216.777 176.9 24");}
            case 5000 -> {if(LIFECYCLE)capture(client,"08-after-reload-and-dimensions");}
            case 5040 -> {if(LIFECYCLE)finish(client);}
        }
    }
    private void finish(Minecraft client) {
        long checks=dev.comfyfluffy.caustica.rt.terrain.NearOwnershipSmokeProbe.checks();
        if(checks<30)throw new IllegalStateException("Insufficient published near/LOD ownership checks");
        if(LIFECYCLE&&(!sawNether||!returned))throw new IllegalStateException("Missing dimension round trip");
        System.out.println("GYSF_PLAYER_VISUAL: PASS frames="+RtComposite.frameCounter()+" ownershipChecks="+checks+" maxHeldNear="+maxHeld+" lifecycle="+LIFECYCLE);
        finished=true;client.disconnectWithSavingScreen();
    }
    private static void teleport(Minecraft client,String position) {client.player.connection.sendCommand("tp @s "+position);}
    private static void capture(Minecraft client,String label) {
        Path folder=Path.of("player-visual",System.getProperty("gysf.smoke.playerVisualLabel","capture"));
        try {Files.createDirectories(folder);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(folder.resolve(label+".png"));}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            finally {image.close();}
        });
        Map<String,Integer> coarse=new TreeMap<>();
        int[] levels=new int[5];
        for(var source:VoxyCompat.meshes()) {
            var mesh=source.textured();if(mesh==null)continue;
            levels[mesh.level()]++;
            if(mesh.level()<2)continue;
            for(int i=0;i<mesh.quadCount();i++) {
                var model=mesh.model((int)(mesh.quad(i)>>>26)&65535);
                String key="L"+mesh.level()+" "+model.state();
                coarse.merge(key,1,Integer::sum);
            }
        }
        var near=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull();
        System.out.println("GYSF_PLAYER_VISUAL: "+label+" clouds="+client.options.getCloudStatus()+" levels="+Arrays.toString(levels)
                +" requestedNear="+client.options.renderDistance().get()+" actualNear="+(near==null?0:PerformanceCapture.nearRadius(near))
                +" heldNear="+(near==null?0:near.retainedHandoffSectionCount())+" handedOff="+(near==null?0:near.completedHandoffCount()));
        coarse.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(12)
                .forEach(e->System.out.println("GYSF_PLAYER_VISUAL: material "+e));
        if(Boolean.getBoolean("gysf.smoke.coarseDiagnostic"))dumpCoarse(client);
    }

    private record Node(long key,int level,int x,int y,int z,int size,int children,int quads) {
        String position(){return x+","+y+","+z+","+size;}
    }
    private static void dumpCoarse(Minecraft client) {
        try {
            var camera=client.gameRenderer.mainCamera().position();
            Object scene=Class.forName("me.cortex.voxy.client.compat.CausticaBridge")
                    .getMethod("pollScene",double.class,double.class,double.class).invoke(null,camera.x,camera.y,camera.z);
            if(scene==null)return;
            Class<?> type=scene.getClass();
            var targets=(List<?>)type.getMethod("meshes").invoke(scene);
            var ancestors=(List<?>)type.getMethod("ancestors").invoke(scene);
            Map<String,Node> nodes=new HashMap<>();List<Node> coarse=new ArrayList<>();
            for(Object mesh:java.util.stream.Stream.concat(targets.stream(),ancestors.stream()).toList()) {
                Class<?> m=mesh.getClass();
                Node n=new Node(m.getField("key").getLong(mesh),m.getField("level").getInt(mesh),
                        (int)m.getMethod("originX").invoke(mesh),(int)m.getMethod("originY").invoke(mesh),
                        (int)m.getMethod("originZ").invoke(mesh),(int)m.getMethod("blockSize").invoke(mesh),
                        m.getField("children").getInt(mesh),(int)m.getMethod("quadCount").invoke(mesh));
                nodes.put(n.position(),n);
                if(n.level>=3 && n.quads>0 && targets.contains(mesh))coarse.add(n);
            }
            var state=client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            var projection=new org.joml.Matrix4f(state.projectionMatrix).mul(state.viewRotationMatrix);
            Class<?> viewType=Class.forName("me.cortex.voxy.api.LodView");
            Object view=viewType.getConstructor(double.class,double.class,double.class,org.joml.Matrix4fc.class,
                    int.class,int.class,float.class).newInstance(camera.x,camera.y,camera.z,projection,
                    client.getWindow().getWidth(),client.getWindow().getHeight(),64f);
            var refine=viewType.getMethod("refine",int.class,int.class,int.class,int.class);
            var distant=dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE;
            var currentField=distant.getClass().getDeclaredField("current");currentField.setAccessible(true);
            Object proxy=currentField.get(distant);Map<Integer,Integer> published=new TreeMap<>();int unwanted=0;
            if(proxy!=null) {
                var entriesField=proxy.getClass().getDeclaredField("entries");entriesField.setAccessible(true);
                for(Object entry:(List<?>)entriesField.get(proxy)) {
                    var dataWidth=entry.getClass().getDeclaredMethod("dataPointWidth");dataWidth.setAccessible(true);
                    int width=(int)dataWidth.invoke(entry);published.merge(width,1,Integer::sum);
                    var volumeMethod=entry.getClass().getDeclaredMethod("volume");volumeMethod.setAccessible(true);
                    Object volume=volumeMethod.invoke(entry);
                    if(volume==null||width<4)continue;
                    int[] bounds=new int[4];String[] names={"x","y","z","width"};
                    for(int i=0;i<4;i++){var method=volume.getClass().getDeclaredMethod(names[i]);method.setAccessible(true);bounds[i]=(int)method.invoke(volume);}
                    if((boolean)refine.invoke(view,bounds[0],bounds[1],bounds[2],bounds[3]))unwanted++;
                }
            }
            System.out.println("GYSF_LOD_PUBLISHED: widths="+published+" unwantedCoarse="+unwanted+" providerRefining="+VoxyCompat.refinementPending());
            if(Boolean.getBoolean("gysf.smoke.rejectGiantLods")&&unwanted>0)
                throw new IllegalStateException("Visible refined branches still covered by "+unwanted+" coarse RT batches");
            for(Node n:coarse) {
                List<String> missing=new ArrayList<>();findMissing(n,nodes,missing);
                double dx=Math.max(Math.max(n.x-camera.x,camera.x-n.x-n.size),0);
                double dz=Math.max(Math.max(n.z-camera.z,camera.z-n.z-n.size),0);
                System.out.println("GYSF_COARSE: L"+n.level+" xyz="+n.position()+" children="+n.children
                        +" quads="+n.quads+" distance="+Math.round(Math.hypot(dx,dz))
                        +" refine="+refine.invoke(view,n.x,n.y,n.z,n.size)+" missing="+missing.stream().limit(12).toList());
            }
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Coarse diagnostic failed",e);}
    }
    private static void findMissing(Node n,Map<String,Node> nodes,List<String> missing) {
        if(n.level==0)return;
        int w=n.size/2;
        for(int c=0;c<8;c++)if((n.children&(1<<c))!=0) {
            String key=(n.x+(c&1)*w)+","+(n.y+((c>>2)&1)*w)+","+(n.z+((c>>1)&1)*w)+","+w;
            Node child=nodes.get(key);
            if(child==null)missing.add(key);else findMissing(child,nodes,missing);
        }
    }
}
