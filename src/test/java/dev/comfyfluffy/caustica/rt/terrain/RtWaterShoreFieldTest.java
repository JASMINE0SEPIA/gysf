package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.compat.VoxyWaterDomain;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;
import static org.junit.jupiter.api.Assertions.*;

class RtWaterShoreFieldTest {
    @Test void coarsePatchCornerInterpolationFailsTheCanonicalUnitCellControl() {
        var field=field(0,0,x->3*smooth(Math.clamp(x/16.0,0,1)));
        float x=7.7871094f;
        var sample=RtWaterShoreField.sample(field,0,0,64,x,4.5f,3);
        int cell=(int)Math.floor(x); double t=smooth(x-cell);
        double left=code(3*smooth(cell/16.0))/3,right=code(3*smooth((cell+1)/16.0))/3;
        assertEquals(left+(right-left)*t,sample.weight(),.000001);
        assertTrue(Math.abs(sample.weight()-smooth(x/16.0))>.009,
                "The control must distinguish canonical sampling from a stretched 16-block interpolation");
    }

    @Test void integerTerrainRebasesKeepWeightsAndGradientsIdentical() {
        var field=field(-16,-16,x->Math.clamp((x+8)*.2,0,3));
        for(float x:new float[]{-7.75f,-.25f,.25f,7.75f,15.25f}) {
            var near=RtWaterShoreField.sample(field,0,0,64,x,.75f,2.2f);
            var rebased=RtWaterShoreField.sample(field,-4096,4096,64,x+4096,.75f-4096,2.2f);
            assertEquals(near,rebased);
        }
    }

    @Test void worldBorderAnchorsRetainSubBlockPrecision() {
        for(int anchor:new int[]{29_999_968,-29_999_968}) {
            var field=field(anchor,0,x->Math.floorMod(x,16)*.1);
            var first=RtWaterShoreField.sample(field,anchor,0,64,7.25f,.5f,2.2f);
            var second=RtWaterShoreField.sample(field,anchor-16,16,64,23.25f,-15.5f,2.2f);
            assertEquals(first,second); assertTrue(first.known());
        }
    }

    @Test void anEditedOwnerNodeIsContinuousFromBothSidesOfATileBoundary() {
        var field=field(0,0,x->x==16 ? 0 : 3);
        var left=RtWaterShoreField.sample(field,0,0,64,15.9999f,4.5f,2.2f);
        var edge=RtWaterShoreField.sample(field,0,0,64,16,4.5f,2.2f);
        var right=RtWaterShoreField.sample(field,0,0,64,16.0001f,4.5f,2.2f);
        assertEquals(0,edge.weight()); assertEquals(0,edge.dx());
        assertEquals(left.weight(),right.weight(),.000001);
        assertEquals(left.dx(),right.dx(),.0013);
    }

    @Test void availableHeadroomLimitsMaximumExcursionAndZeroStrengthStaysFinite() {
        var field=field(0,0,x->.5);
        for(float bound:new float[]{.1375f,.55f,2.2f}) {
            var sample=RtWaterShoreField.sample(field,0,0,64,4.25f,4.5f,bound);
            assertTrue(sample.weight()*bound<=.50003f);
        }
        var flat=RtWaterShoreField.sample(field,0,0,64,4.25f,4.5f,0);
        assertEquals(0,flat.weight()); assertEquals(0,flat.dx()); assertEquals(0,flat.dz());
    }

    @Test void gradientsMatchFiniteDifferencesAndMissingDataIsExplicit() {
        var field=field(0,0,x->x*.1);
        float x=4.25f,eps=.001f;
        var center=RtWaterShoreField.sample(field,0,0,64,x,4.5f,2.2f);
        float difference=(RtWaterShoreField.sample(field,0,0,64,x+eps,4.5f,2.2f).weight()
                -RtWaterShoreField.sample(field,0,0,64,x-eps,4.5f,2.2f).weight())/(2*eps);
        assertEquals(difference,center.dx(),.00002);
        var missing=RtWaterShoreField.sample(null,0,0,64,x,4.5f,2.2f);
        assertFalse(missing.known()); assertEquals(0,missing.weight());
        assertThrows(IllegalArgumentException.class,()->RtWaterShoreField.sample(field,0,0,64,Float.NaN,0,1));
    }

    private static VoxyWaterDomain.Snapshot field(int baseX,int baseZ,IntToDoubleFunction clearance) {
        var tiles=new ArrayList<VoxyWaterDomain.Tile>();
        for(int tz=Math.floorDiv(baseZ,16);tz<=Math.floorDiv(baseZ,16)+2;tz++)
            for(int tx=Math.floorDiv(baseX,16);tx<=Math.floorDiv(baseX,16)+2;tx++) {
                short[] values=new short[256];
                for(int z=0;z<16;z++) for(int x=0;x<16;x++) values[z*16+x]=(short)Math.round(Math.clamp(clearance.applyAsDouble(tx*16+x),0,3)/3*65535);
                tiles.add(new VoxyWaterDomain.Tile(tx,64,tz,1,values,new long[]{-1,-1,-1,-1}));
            }
        return new VoxyWaterDomain.Snapshot(1,1,List.copyOf(tiles),0);
    }
    private static double smooth(double x) { return x*x*(3-2*x); }
    private static double code(double value) { return Math.round(value/3*65535)*3.0/65535; }
}
