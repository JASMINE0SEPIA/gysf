package dev.comfyfluffy.caustica.rt;

/** Wave-domain transport. Reduce each linear phase, never the incommensurate world coordinates. */
final class RtWaterWaveDomain {
    static final int WAVE_COUNT = 7;
    private static final Basis[] BASIS = basis();

    record Component(float kx, float kz, float mx, float mz, float mainPhase, float meanderPhase, float omega) {}
    private record Basis(float kx, float kz, float mx, float mz, float omega) {}

    private RtWaterWaveDomain() {}

    static Component[] create(int originX, int originZ) {
        return create(originX, originZ, 0, 1);
    }

    static Component[] create(int originX, int originZ, double baseSeconds, float speed) {
        Component[] result = new Component[WAVE_COUNT];
        double scaledBase = baseSeconds * (0.8f * speed);
        for (int i = 0; i < result.length; i++) {
            Basis b = BASIS[i];
            // The coefficients themselves are FP32 and shared with the shader. Multiplying by the
            // full integer origin in double preserves sub-block phases even at the world border.
            float main = phase((double)b.kx * originX + (double)b.kz * originZ - b.omega * scaledBase);
            float meander = phase((double)b.mx * originX + (double)b.mz * originZ + (0.47f * b.omega) * scaledBase);
            result[i] = new Component(b.kx, b.kz, b.mx, b.mz, main, meander, b.omega);
        }
        return result;
    }

    private static float phase(double radians) {
        return (float)Math.IEEEremainder(radians, 2.0 * Math.PI);
    }

    private static Basis[] basis() {
        // Authored spectrum mirrored from water.slang. The production Slang domain probe compares
        // these coefficients with its authored basis as well as checking transported field values.
        Basis[] result = new Basis[WAVE_COUNT];
        float lambda = 28.0f;
        float windLength = (float)Math.sqrt(1.0f + 0.35f * 0.35f);
        float wx = 1.0f / windLength, wz = 0.35f / windLength;
        for (int i = 0; i < result.length; i++) {
            float k = 2.0f * (float)Math.PI / lambda;
            float spread = (0.28f + 0.22f * i) * (float)Math.sin(i * 2.399f + 1.3f)
                    + 0.31f * (float)Math.sin(i * 3.883f + 0.6f)
                    + 0.17f * (float)Math.sin(i * 5.236f + 2.1f);
            float cs = (float)Math.cos(spread), sn = (float)Math.sin(spread);
            float dx = wx * cs - wz * sn, dz = wx * sn + wz * cs;
            result[i] = new Basis(k * dx, k * dz, -(0.37f * k) * dz, (0.37f * k) * dx, (float)Math.sqrt(9.81f * k));
            lambda /= 1.618034f;
        }
        return result;
    }
}
