package dev.comfyfluffy.caustica.rt.terrain;
import dev.comfyfluffy.caustica.compat.VoxyWaterDomain.Tile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtWaterFieldRequestsTest {
    @Test void overflowRemainsBoundedAndIndependentOfTraversalOrder() {
        var a=new RtWaterFieldRequests(32,8,0,8,-64,320);var b=new RtWaterFieldRequests(32,8,0,8,-64,320);
        for(int i=-6000;i<6000;i++) {
            a.accept(Tile.key(i,-1,0));a.accept(Tile.key(i,-1,0));
            b.accept(Tile.key(-i-1,-1,0));
            assertTrue(a.size()<=32);assertTrue(b.size()<=32);
        }
        assertArrayEquals(a.triples(),b.triples());assertEquals(96,a.triples().length);
        int[] expected=new int[96];
        for(int i=0;i<32;i++){expected[i*3]=i==0?0:(i&1)==1?-(i+1)/2:i/2;expected[i*3+1]=-1;}
        assertArrayEquals(expected,a.triples());
    }
    @Test void heightAndBorderCoordinatesParticipateWithoutFloatRounding() {
        var requests=new RtWaterFieldRequests(1,30000000.25,177,-30000000.25,0,256);
        requests.accept(Tile.key(1875000,175,-1875001));requests.accept(Tile.key(1875000,176,-1875001));
        assertArrayEquals(new int[]{1875000,176,-1875001},requests.triples());
        assertThrows(IllegalArgumentException.class,()->new RtWaterFieldRequests(4097,0,0,0,0,256));
    }
    @Test void worldHeightChangesDropOldFallbackLayersBeforeProviderPolling() {
        var overworld=new RtWaterFieldRequests(32,0,90,0,-64,320);
        var nether=new RtWaterFieldRequests(32,0,90,0,0,256);
        for(int y:new int[]{-65,-64,-1,0,255,256,319,320}) {
            long key=Tile.key(0,y,0);overworld.accept(key);nether.accept(key);
        }
        assertEquals(6,overworld.size());assertEquals(2,nether.size());
        int[] layers=nether.triples();assertEquals(0,layers[1]);assertEquals(255,layers[4]);
        assertThrows(IllegalArgumentException.class,()->new RtWaterFieldRequests(1,0,0,0,256,256));
    }
}
