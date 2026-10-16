package dev.comfyfluffy.caustica.rt.terrain;

/** Shared near/LOD water input for procedural traversal. Coordinates remain section-local. */
public final class RtWaterGeometry {
    private static final boolean RUNTIME_ENABLED=Boolean.getBoolean("gysf.rt.finiteWater");
    /** Development gate until the shared shore field and optical/guide paths are qualified. */
    public static boolean runtimeEnabled() { return RUNTIME_ENABLED; }
    /** Three position/metadata lanes, quad rectangle, shared shoreline weights. */
    public static final int PRIMITIVE_FLOATS = 20;
    public static final int PRIMITIVE_BYTES = PRIMITIVE_FLOATS * Float.BYTES;
    public static final int AABB_FLOATS = 6;
    public static final float MAX_DISPLACEMENT = 2.2f; // authored 0.55 bound * maximum height slider 4
    private static final float PADDING = 0.0001f;

    public record Packed(float[] aabbs, float[] primitives, int topTriangles) {
        public int triangleCount() { return aabbs.length / AABB_FLOATS; }
        public long bytes() { return ((long)aabbs.length + primitives.length) * Float.BYTES; }

    }

    private RtWaterGeometry() {}

    /** Immutable CPU input for optional derived BLAS bounds; no shader attributes are duplicated. */
    public static final class BoundsInput {
        private final float[] wide,top;
        private BoundsInput(Packed packed) {
            wide=packed.aabbs().clone();top=new float[packed.triangleCount()];
            java.util.Arrays.fill(top,Float.NaN);
            for(int t=0;t<top.length;t++) {
                int p=t*PRIMITIVE_FLOATS;
                if(packed.primitives()[p+3]!=0)top[t]=Math.max(packed.primitives()[p+1],
                        Math.max(packed.primitives()[p+5],packed.primitives()[p+9]));
            }
        }
        public int count(){return top.length;}
        public long bytes(){return ((long)wide.length+top.length)*Float.BYTES;}
        public float[] atHeight(float height) {
            if(!Float.isFinite(height)||height<0||height>MAX_DISPLACEMENT)throw new IllegalArgumentException("Invalid water height envelope");
            float[] result=wide.clone();
            for(int t=0;t<top.length;t++)if(!Float.isNaN(top[t]))result[t*6+4]=Math.nextUp(top[t]+height+PADDING);
            return result;
        }
    }
    public static BoundsInput boundsInput(Packed packed) {return new BoundsInput(packed);}

    /**
     * Water quads must be contiguous pairs in the existing packed index order. An optional envelope
     * contains four values per quad: x0z0, x0z1, x1z0, x1z1. Missing weights mean fully wet; callers
     * must supply a shared world-space shoreline policy before enabling the complete renderer path.
     */
    public static Packed pack(float[] positions, int[] indices, int firstTriangle, int triangleCount,
                              float[] shoreWeights) {
        if (positions.length % 3 != 0 || indices.length % 3 != 0 || firstTriangle < 0
                || triangleCount < 0 || triangleCount % 2 != 0
                || ((long)firstTriangle + triangleCount) * 3 > indices.length) {
            throw new IllegalArgumentException("invalid packed water triangle range");
        }
        int quadCount = triangleCount / 2;
        if (shoreWeights != null && shoreWeights.length != Math.multiplyExact(quadCount, 4)) {
            throw new IllegalArgumentException("water shore envelope does not match quad count");
        }
        if (shoreWeights != null) {
            for (float w : shoreWeights) {
                if (!Float.isFinite(w) || w < 0 || w > 1) {
                    throw new IllegalArgumentException("water shore weights must be finite and in [0,1]");
                }
            }
        }
        float[] boxes = new float[Math.multiplyExact(triangleCount, AABB_FLOATS)];
        float[] primitives = new float[Math.multiplyExact(triangleCount, PRIMITIVE_FLOATS)];
        int tops = 0;
        for (int quad = 0; quad < quadCount; quad++) {
            int indexStart = (firstTriangle + quad * 2) * 3;
            float minX = Float.POSITIVE_INFINITY, minZ = minX;
            float maxX = Float.NEGATIVE_INFINITY, maxZ = maxX,maxY=maxX;
            for (int corner = 0; corner < 6; corner++) {
                int vertex = indices[indexStart + corner];
                if (vertex < 0 || vertex >= positions.length / 3) {
                    throw new IllegalArgumentException("water vertex index out of range");
                }
                int offset = vertex * 3;
                for (int axis = 0; axis < 3; axis++) {
                    if (!Float.isFinite(positions[offset + axis])) {
                        throw new IllegalArgumentException("water positions must be finite");
                    }
                }
                minX = Math.min(minX, positions[offset]); maxX = Math.max(maxX, positions[offset]);
                minZ = Math.min(minZ, positions[offset + 2]); maxZ = Math.max(maxZ, positions[offset + 2]);
                maxY=Math.max(maxY,positions[offset+1]);
            }
            float width = maxX - minX, depth = maxZ - minZ;
            for (int half = 0; half < 2; half++) {
                int triangle = quad * 2 + half;
                int target = triangle * PRIMITIVE_FLOATS;
                for (int v = 0; v < 3; v++) {
                    int source = indices[indexStart + half * 3 + v] * 3;
                    System.arraycopy(positions, source, primitives, target + v * 4, 3);
                }
                float ax = primitives[target + 4] - primitives[target];
                float ay = primitives[target + 5] - primitives[target + 1];
                float az = primitives[target + 6] - primitives[target + 2];
                float bx = primitives[target + 8] - primitives[target];
                float by = primitives[target + 9] - primitives[target + 1];
                float bz = primitives[target + 10] - primitives[target + 2];
                float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                double length = Math.sqrt((double)nx * nx + (double)ny * ny + (double)nz * nz);
                if (!(length > 0) || !Double.isFinite(length)) {
                    throw new IllegalArgumentException("degenerate water triangle");
                }
                boolean top = ny / length >= 0.5;
                if (top) {
                    if (!(width > 0 && depth > 0)) {
                        throw new IllegalArgumentException("free water top has no XZ extent");
                    }
                    int mask = 0;
                    for (int v = 0; v < 3; v++) {
                        float x = primitives[target + v * 4], z = primitives[target + v * 4 + 2];
                        if ((x != minX && x != maxX) || (z != minZ && z != maxZ)) {
                            throw new IllegalArgumentException("free water top is not a rectangle half");
                        }
                        mask |= 1 << ((x == maxX ? 2 : 0) | (z == maxZ ? 1 : 0));
                    }
                    int shape = switch (mask) {
                        case 0b1101 -> 0; // 00,10,11: u>=v
                        case 0b1011 -> 1; // 00,01,11: u<=v
                        case 0b0111 -> 2; // 00,01,10: u+v<=1
                        case 0b1110 -> 3; // 01,10,11: u+v>=1
                        default -> throw new IllegalArgumentException("invalid free water triangle corners");
                    };
                    primitives[target + 3] = 1;
                    primitives[target + 7] = shape;
                    tops++;
                }
                primitives[target + 12] = minX; primitives[target + 13] = minZ;
                // Shared by both halves, including nonplanar quads and exact full-cell tops.
                // A fixed epsilon fails once the local float ULP exceeds that epsilon.
                primitives[target+11]=(float)Math.floor(Math.nextDown(maxY));
                primitives[target + 14] = width; primitives[target + 15] = depth;
                for (int v = 0; v < 4; v++) {
                    primitives[target + 16 + v] = shoreWeights == null ? 1 : shoreWeights[quad * 4 + v];
                }
                int box = triangle * AABB_FLOATS;
                for (int axis = 0; axis < 3; axis++) {
                    float minimum = Math.min(primitives[target + axis],
                            Math.min(primitives[target + 4 + axis], primitives[target + 8 + axis]));
                    float maximum = Math.max(primitives[target + axis],
                            Math.max(primitives[target + 4 + axis], primitives[target + 8 + axis]));
                    boxes[box + axis] = Math.nextDown(minimum - PADDING);
                    boxes[box + 3 + axis] = Math.nextUp(maximum + (top && axis == 1 ? MAX_DISPLACEMENT : 0) + PADDING);
                }
            }
        }
        return new Packed(boxes, primitives, tops);
    }
}
