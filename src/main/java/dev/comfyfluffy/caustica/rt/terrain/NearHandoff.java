package dev.comfyfluffy.caustica.rt.terrain;

/** Keep already drawn exact terrain until a published far source can replace it, within the horizon. */
final class NearHandoff {
    private NearHandoff() {}
    static boolean retain(boolean externalActive,boolean publishedFar,int dx,int dz,int horizonChunks) {
        int radius=Math.clamp(horizonChunks,1,64);
        return externalActive && !publishedFar && (long)dx*dx+(long)dz*dz<=(long)radius*radius;
    }
}
