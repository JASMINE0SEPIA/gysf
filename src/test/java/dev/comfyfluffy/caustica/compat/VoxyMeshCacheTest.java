package dev.comfyfluffy.caustica.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VoxyMeshCacheTest {
    private static final long QUAD = 1L << 26;
    private static VoxySceneData.Model model(int face, int flags, int tint) {
        return new VoxySceneData.Model(1,face,face,face,face,face,face,flags,tint,null,false,false,false,0);
    }
    private static VoxySceneData.Palette palette(VoxySceneData.Model model, int... colours) {
        return new VoxySceneData.Palette(new VoxySceneData.Model[]{null,model},colours);
    }
    private static VoxyMeshCache.Entry put(VoxyMeshCache cache,long key,long revision,VoxySceneData.Palette palette) {
        return cache.put(key,revision,new long[]{QUAD},new long[0],0,palette);
    }

    @Test void unchangedSourceKeepsIdentityAcrossOtherViewsAndEquivalentPaletteObjects() {
        var cache=new VoxyMeshCache(4096);var model=model(0,0,-1);var palette=palette(model);
        var first=put(cache,1,7,palette);put(cache,2,8,palette);
        var returned=cache.get(1,7,palette(model));
        assertSame(first,returned);
        assertEquals(first.version(),put(cache,1,9,palette(model)).version());
    }
    @Test void addingUnreferencedModelsAndTintEntriesDoesNotInvalidateExistingGeometry() {
        var cache=new VoxyMeshCache(4096);var model=model(0,0,-1);var first=put(cache,1,1,palette(model));
        var expanded=new VoxySceneData.Palette(new VoxySceneData.Model[]{model,model,model},new int[]{42,43});
        assertSame(first,cache.get(1,1,expanded));
    }
    @Test void changedUsedModelOrBiomeColourInvalidatesOnlyItsOwnSource() {
        var cache=new VoxyMeshCache(4096);var tinted=model(0,2,0);var original=palette(tinted,0x123456,10);
        var first=put(cache,1,1,original);
        assertSame(first,cache.get(1,1,palette(tinted,0x123456,99)));
        var changed=palette(tinted,0x654321,10);
        assertNull(cache.get(1,1,changed));
        var recoloured=put(cache,1,1,changed);
        assertNotEquals(first.version(),recoloured.version());
        var reshaped=put(cache,1,1,palette(model(17,2,0),0x654321));
        assertNotEquals(recoloured.version(),reshaped.version());
    }
    @Test void geometryPreciseFluidAndLodChangesCannotReuseOldVersion() {
        var cache=new VoxyMeshCache(4096);var palette=palette(model(0,0,-1));
        var a=put(cache,1,1,palette);
        var b=cache.put(1,2,new long[]{QUAD|1},new long[0],0,palette);
        var c=cache.put(1,3,new long[]{QUAD|1},new long[]{1234},0,palette);
        var d=cache.put(1,4,new long[]{QUAD|1},new long[]{1234},1,palette);
        assertNotEquals(a.version(),b.version());assertNotEquals(b.version(),c.version());assertNotEquals(c.version(),d.version());
    }
    @Test void epochClearDropsDataAndDoesNotReuseAFormerIdentity() {
        var cache=new VoxyMeshCache(4096);var palette=palette(model(0,0,-1));var first=put(cache,1,1,palette);
        cache.clear();assertEquals(0,cache.bytes());assertNull(cache.get(1,1,palette));
        assertNotEquals(first.version(),put(cache,1,1,palette).version());
    }
    @Test void lruEvictionBoundsBytesWhileConsumersKeepTheirImmutableSnapshot() {
        var palette=palette(model(0,0,-1));var probe=new VoxyMeshCache(4096);long size=put(probe,1,1,palette).mesh().retainedBytes();
        var cache=new VoxyMeshCache(size*2);var retained=put(cache,1,1,palette);put(cache,2,2,palette);
        cache.get(1,1,palette);put(cache,3,3,palette);
        assertNull(cache.get(2,2,palette));assertSame(retained,cache.get(1,1,palette));
        assertEquals(size*2,cache.bytes());assertEquals(QUAD,retained.mesh().quad(0));
    }
    @Test void mutationOfProviderArraysCannotChangeCachedContent() {
        var cache=new VoxyMeshCache(4096);var palette=palette(model(0,0,-1));long[] quads={QUAD},fluids={1234};
        var source=cache.put(1,1,quads,fluids,0,palette);quads[0]=0;fluids[0]=0;
        assertEquals(QUAD,source.mesh().quad(0));assertEquals(1234,source.mesh().fluidHeights(0));
    }
}
