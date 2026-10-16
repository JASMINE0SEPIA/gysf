package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.compat.VoxyWaterDomain;
import dev.comfyfluffy.caustica.rt.terrain.RtWaterShoreField;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Source field export and owned canonical sampling; this does not enable GPU shoreline binding. */
final class WaterDomainSmokeProbe {
    static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.waterDomain");
    private static final int[] REQUESTS={3,176,2, 3,176,3, 4,176,2, 4,176,3, 2,176,0, 4,175,3, 4096,176,4096};
    private VoxyWaterDomain.Snapshot snapshot;
    private VoxyWaterDomain.Tile initial,edited;
    private java.util.concurrent.CompletableFuture<?> edit,restore;
    private boolean initialChecked,editedChecked,restoredChecked;
    private int maxPending;

    void tick(Minecraft client,int tick) {
        if(!ENABLED) return;
        if(tick>=650 && tick<=1250) {
            if(!VoxyWaterDomain.supported()) throw new IllegalStateException("Provider lacks canonical water capability 1");
            snapshot=VoxyWaterDomain.poll(REQUESTS);
            if(snapshot!=null) maxPending=Math.max(maxPending,snapshot.pending());
        }
        if(tick==900) {
            initial=required(4,176,3);
            if(!initial.complete() || initial.clearance(0,0)!=3) throw new IllegalStateException("Open water field was not completely sourced");
            var shore=required(2,176,0);
            if(!shore.known(1,2) || shore.clearance(1,2)!=0 || shore.clearance(2,2)!=3)
                throw new IllegalStateException("Canonical shore did not meet the actual quartz rim");
            if(required(4,175,3).clearance(0,0)!=0) throw new IllegalStateException("Covered underwater layer was treated as a free surface");
            var unknown=required(4096,176,4096);
            if(unknown.complete() || unknown.known(0,0) || unknown.clearance(0,0)!=0)
                throw new IllegalStateException("Unvisited source data was reported as known water");
            compareRebases(); initialChecked=true;
            System.out.println("GYSF_WATER_DOMAIN: initial PASS epoch="+snapshot.epoch()+" revision="+snapshot.revision()+" tiles="+snapshot.tiles().size());
        }
        if(tick==950) edit=client.getSingleplayerServer().submit(()->
                client.getSingleplayerServer().overworld().setBlock(new BlockPos(64,178,48),Blocks.GLASS.defaultBlockState(),2));
        if(tick==1070) {
            if(edit==null || !edit.isDone()) throw new IllegalStateException("Water ceiling edit incomplete");
            edit.join(); edited=required(4,176,3);
            if(edited.revision()<=initial.revision() || Math.abs(edited.clearance(0,0)-1)>.00005f)
                throw new IllegalStateException("Overhead edit left a stale canonical field");
            if(initial.clearance(0,0)!=3) throw new IllegalStateException("Published water field was mutated");
            compareRebases(); editedChecked=true;
            System.out.println("GYSF_WATER_DOMAIN: ceiling-edit PASS revision="+edited.revision()+" clearance="+edited.clearance(0,0));
        }
        if(tick==1100) restore=client.getSingleplayerServer().submit(()->
                client.getSingleplayerServer().overworld().setBlock(new BlockPos(64,178,48),Blocks.AIR.defaultBlockState(),2));
        if(tick==1230) {
            if(restore==null || !restore.isDone()) throw new IllegalStateException("Water ceiling restoration incomplete");
            restore.join(); var restored=required(4,176,3);
            if(restored.revision()<=edited.revision() || restored.clearance(0,0)!=3 || edited.clearance(0,0)!=1)
                throw new IllegalStateException("Restored field did not retain immutable previous data");
            compareRebases(); restoredChecked=true;
            System.out.println("GYSF_WATER_DOMAIN: restoration PASS revision="+restored.revision());
        }
    }

    void verify() {
        if(!ENABLED) return;
        if(!initialChecked || !editedChecked || !restoredChecked) throw new IllegalStateException("Water domain phases incomplete");
        System.out.println("GYSF_WATER_DOMAIN: PASS phases=3 maxPending="+maxPending+" scope=CPU-source-export-and-canonical-sampling");
    }

    private VoxyWaterDomain.Tile required(int x,int y,int z) {
        var tile=snapshot==null ? null : snapshot.tile(x,y,z);
        if(tile==null) throw new IllegalStateException("Water domain tile missing: "+x+","+y+","+z);
        return tile;
    }
    private void compareRebases() {
        for(float x:new float[]{63.75f,64,64.25f}) for(float z:new float[]{47.75f,48,48.25f}) {
            var near=RtWaterShoreField.sample(snapshot,0,0,176,x,z,2.2f);
            var far=RtWaterShoreField.sample(snapshot,-4096,4096,176,x+4096,z-4096,2.2f);
            if(!near.known() || !near.equals(far)) throw new IllegalStateException("World field changed with its terrain anchor");
        }
    }
}
