package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NearHandoffTest {
    @Test void windowExitKeepsVisibleGeometryUntilItsFarReplacementIsPublished() {
        assertTrue(NearHandoff.retain(true,false,24,0,64));
        assertTrue(NearHandoff.retain(true,false,-24,24,64));
        assertFalse(NearHandoff.retain(true,true,24,0,64));
        assertFalse(NearHandoff.retain(false,false,24,0,64));
    }
    @Test void fallbackRetentionIsBoundedByTheNominalHorizon() {
        assertTrue(NearHandoff.retain(true,false,64,0,64));
        assertFalse(NearHandoff.retain(true,false,64,1,64));
        assertFalse(NearHandoff.retain(true,false,65,0,10000));
        assertFalse(NearHandoff.retain(true,false,33,0,32));
    }
    @Test void coverageRequiresTheSameThreeDimensionalPublishedSource() {
        var index=new VoxyCoverageIndex(java.util.List.of(new VoxyLodTransition.Volume(1,-64,-64,0,64)));
        for(int x=-4;x<0;x++)for(int y=-4;y<0;y++)for(int z=0;z<4;z++)assertTrue(index.containsSection(x,y,z));
        assertFalse(index.containsSection(0,-1,0));
        assertFalse(index.containsSection(-1,0,0));
        assertFalse(index.containsSection(-1,-1,-1));
        assertFalse(new VoxyCoverageIndex(java.util.List.of()).containsSection(-1,-1,0));
    }
}
