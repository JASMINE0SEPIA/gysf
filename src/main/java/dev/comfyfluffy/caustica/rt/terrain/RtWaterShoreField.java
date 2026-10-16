package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.compat.VoxyWaterDomain;

/** Canonical unit-cell sampling; independent of a water primitive's size or diagonal. */
public final class RtWaterShoreField {
    private RtWaterShoreField() {}
    public record Sample(float weight,float dx,float dz,boolean known) {}

    /** Integer world anchors keep sub-block precision near the world border and across terrain rebases. */
    public static Sample sample(VoxyWaterDomain.Snapshot field,int anchorX,int anchorZ,int waterY,
                                float localX,float localZ,float maximumDisplacement) {
        if(!Float.isFinite(localX) || !Float.isFinite(localZ) || !Float.isFinite(maximumDisplacement) || maximumDisplacement<0
                || localX<Integer.MIN_VALUE || localX>=Integer.MAX_VALUE || localZ<Integer.MIN_VALUE || localZ>=Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid canonical water sample");
        int cx=(int)Math.floor(localX),cz=(int)Math.floor(localZ);
        int x=Math.addExact(anchorX,cx),z=Math.addExact(anchorZ,cz);
        float u=localX-cx,v=localZ-cz,s=u*u*(3-2*u),t=v*v*(3-2*v);
        float[] w=new float[4]; boolean known=true;
        for(int i=0;i<4;i++) {
            int nx=Math.addExact(x,i>>>1),nz=Math.addExact(z,i&1);
            var tile=field==null ? null : field.tile(Math.floorDiv(nx,16),waterY,Math.floorDiv(nz,16));
            boolean present=tile!=null && tile.known(Math.floorMod(nx,16),Math.floorMod(nz,16));
            known &= present;
            float clearance=present ? tile.clearance(Math.floorMod(nx,16),Math.floorMod(nz,16)) : 0;
            w[i]=maximumDisplacement==0 ? 0 : Math.min(clearance/maximumDisplacement,1);
        }
        float a=w[0]+(w[1]-w[0])*t,b=w[2]+(w[3]-w[2])*t;
        return new Sample(a+(b-a)*s,(b-a)*6*u*(1-u),
                ((w[1]-w[0])+(w[3]-w[2]-w[1]+w[0])*s)*6*v*(1-v),known);
    }
}
