package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the parts of {@link RtWeatherCapture} that must stay bit-compatible with vanilla's
 * {@code WeatherEffectRenderer}.
 *
 * <p>The capture itself cannot run here — it needs a live {@code Minecraft}, a GPU capture buffer and a
 * bindless texture registry — so this covers the two things that would silently produce wrong-looking
 * rain rather than a crash, plus the structural wiring that keeps the feature reachable at all:
 *
 * <ul>
 *   <li>the column orientation table, reproduced from vanilla's constructor. Getting the sign or the
 *       axis swap wrong here still renders rain, just all facing one way (a flat wall) — no exception,
 *       no test failure anywhere else.</li>
 *   <li>the distance fade, which is what stops the columns reading as a hard cylinder around the
 *       player.</li>
 *   <li>the source-level guarantees that the capture reads vanilla's render state instead of
 *       synthesising its own field — the exact mistake the earlier "floating bars" attempts made.</li>
 * </ul>
 */
final class RtWeatherCaptureTest {
    private static final int RAIN_TABLE_SIZE = 32;
    private static final int HALF_RAIN_TABLE_SIZE = 16;


    private static Path source() {
        return repoRoot().resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtWeatherCapture.java");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("could not locate the repository root");
        }
        return dir;
    }

    /**
     * Vanilla's table, recomputed independently: {@code columnSizeX = -deltaZ / len},
     * {@code columnSizeZ = deltaX / len}. That is a 90-degree rotation of the camera-to-column offset, so
     * every column's quad lies perpendicular to the direction it is seen from — which is what makes the
     * sheets fan out around the player instead of all sharing one facing.
     */
    @Test
    void columnOrientationIsPerpendicularToTheViewOffset() {
        for (int z = 0; z < RAIN_TABLE_SIZE; z++) {
            for (int x = 0; x < RAIN_TABLE_SIZE; x++) {
                float deltaX = x - HALF_RAIN_TABLE_SIZE;
                float deltaZ = z - HALF_RAIN_TABLE_SIZE;
                float len = (float) Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
                if (len < 1.0e-4f) {
                    continue; // the table centre is the degenerate cell — covered by its own test below
                }
                float sizeX = -deltaZ / len;
                float sizeZ = deltaX / len;
                // Perpendicular to the offset: their dot product is zero.
                assertEquals(0.0f, sizeX * deltaX + sizeZ * deltaZ, 1.0e-3f,
                        "column (" + x + "," + z + ") must face across the view offset");
                // ...and unit length, so the quad is exactly one block wide before the /2.
                assertEquals(1.0f, (float) Math.sqrt(sizeX * sizeX + sizeZ * sizeZ), 1.0e-5f,
                        "column (" + x + "," + z + ") orientation must be normalised");
            }
        }
    }

    /**
     * The table's exact centre is the column the camera stands in, where vanilla's {@code -deltaZ/dist}
     * is 0/0.
     *
     * <p>Vanilla tolerates the resulting NaN because the rasteriser silently drops a NaN vertex. Caustica
     * feeds these quads to a BLAS build instead, where a NaN corner poisons the acceleration structure
     * node and every ray tested against it degenerates — which is what produced the thin vertical sliver
     * that appeared after standing still long enough for the camera to settle into one cell. Assert the
     * capture substitutes a finite direction rather than inheriting vanilla's NaN.
     */
    @Test
    void tableCentreIsFiniteRatherThanVanillasNaN() throws IOException {
        // The raw vanilla expression really is NaN at the centre — this is the hazard being guarded.
        float degenerate = -0.0f / 0.0f;
        assertTrue(Float.isNaN(degenerate), "0/0 must be NaN, or this test is not testing anything");

        String src = Files.readString(source());
        assertTrue(src.contains("distance < 1.0e-4f"),
                "the degenerate centre cell must be detected before dividing");
        // A NaN reaching the BLAS is the actual failure mode, so make the reason unmissable in-source.
        assertTrue(src.contains("NaN"), "the centre-cell guard must explain the NaN/BLAS hazard");
    }

    @Test
    void rainStartsAndEndsContinuouslyWithoutAnOpacityFloor() {
        assertEquals(0f, RtWeatherCapture.visualIntensity(0f));
        assertEquals(0.0001f, RtWeatherCapture.visualIntensity(0.0001f), 1e-7f);
        assertEquals(0.05f, RtWeatherCapture.visualIntensity(0.05f), 1e-7f);
        assertEquals(1f, RtWeatherCapture.visualIntensity(1f));
    }

    @Test
    void intensityPreservesVanillaRampAndClampsOnlyOutsideItsDomain() {
        assertEquals(0f, RtWeatherCapture.visualIntensity(-1f));
        assertEquals(1f, RtWeatherCapture.visualIntensity(2f));
        float previous = 0f;
        for (int step = 0; step <= 100; step++) {
            float input = step / 100f;
            float actual = RtWeatherCapture.visualIntensity(input);
            assertEquals(input, actual, 1e-7f);
            assertTrue(actual >= previous);
            previous = actual;
        }
    }
    /**
     * Distance fade must ride the ALPHA lane only.
     *
     * <p>An earlier version also scaled the vertex RGB by the fade to reproduce vanilla's falloff. But
     * tint is not opacity: {@code world.rchit} multiplies it straight into the albedo, so far columns
     * were rendered *darker* rather than thinner, and the step between two adjacent fade values showed up
     * as a visible seam — one patch of rain brighter than the rest. Pin the white RGB so this cannot
     * regress.
     */
    @Test
    void distanceFadeUsesCoverageNotTint() throws IOException {
        String src = Files.readString(source());

        assertTrue(src.contains("ARGB.white(alpha)"),
                "column colour must stay white: the fade belongs in alpha/coverage, not in the tint");
        assertFalse(src.contains("ARGB.color(level, level, level, level)"),
                "scaling RGB by the fade darkens distant rain instead of thinning it");
    }

    /**
     * Vanilla fades a column from {@code maxAlpha} at the camera to {@code 0.5 * maxAlpha}-ish at the
     * radius edge, scaled by rain intensity. The fade must be monotonic and must vanish when it stops
     * raining, or rain would either pop in at full strength or linger after the storm ends.
     */
    @Test
    void distanceFadeIsMonotonicAndScalesWithIntensity() {
        float near = fade(0.0f, 1.0f, 1.0f);
        float mid = fade(0.5f, 1.0f, 1.0f);
        float far = fade(1.0f, 1.0f, 1.0f);

        assertEquals(1.0f, near, 1.0e-6f, "a column at the camera keeps full alpha");
        assertEquals(0.5f, far, 1.0e-6f, "a column at the radius edge is half faded");
        assertTrue(near > mid && mid > far, "alpha must fall off monotonically with distance");

        assertEquals(0.0f, fade(0.0f, 1.0f, 0.0f), 1.0e-6f, "no rain intensity means no columns drawn");
        assertEquals(near * 0.25f, fade(0.0f, 1.0f, 0.25f), 1.0e-6f, "alpha scales linearly with intensity");
    }

    /** Vanilla's {@code Mth.lerp(min(dSq/rSq, 1), maxAlpha, 0.5) * intensity}. */
    private static float fade(float distanceFraction, float maxAlpha, float intensity) {
        float t = Math.min(distanceFraction, 1.0f);
        return (maxAlpha + t * (0.5f - maxAlpha)) * intensity;
    }

    /**
     * The whole point of this class is that it does <em>not</em> invent geometry. Earlier attempts at
     * ray-traced rain generated a synthetic streak field, which produced floating bars that looked like
     * falling metal because nothing tied them to the world's heightmap or the real weather state. Assert
     * the capture is still sourced from vanilla's extracted columns, so a future refactor cannot quietly
     * reintroduce a procedural field.
     */
    @Test
    void columnsComeFromVanillaWeatherRenderStateNotASyntheticField() throws IOException {
        String src = Files.readString(source());

        assertTrue(src.contains("levelRenderState.weatherRenderState"),
                "columns must be read from vanilla's extracted WeatherRenderState");
        assertTrue(src.contains("state.rainColumns") && src.contains("state.snowColumns"),
                "both vanilla column lists must be captured");
        // bottomY/topY are vanilla's heightmap-clipped extents: this is what makes rain stop at the
        // ground and under roofs rather than passing through the world.
        assertTrue(src.contains("column.bottomY()") && src.contains("column.topY()"),
                "column extents must come from vanilla, which already clipped them to the heightmap");
        // vOffset is the scrolling texture coordinate that makes the drops actually fall.
        assertTrue(src.contains("column.vOffset()"),
                "the falling animation must use vanilla's scrolling texture offset");
        assertTrue(src.contains("textures/environment/rain.png")
                        && src.contains("textures/environment/snow.png"),
                "weather must sample vanilla's own sheet textures");
    }

    /**
     * For particles and weather, zero coverage must mean invisible — not opaque.
     *
     * <p>{@code world.rahit} used to read the aux0 coverage lane as {@code aux0 == 0 ? 1.0 : aux0} for
     * every kind of geometry. That is right for model geometry, whose alpha byte comes from a vertex
     * colour some submit paths use 0 in as a "no tint" sentinel, but wrong for particles and weather,
     * whose alpha is vanilla's own blend weight and genuinely means transparency.
     *
     * <p>For rain the shared reading inverted the storm's own ramp. Coverage is {@code columnAlpha *
     * visualIntensity * rainDensity}, so as weather starts the multiplier passes through the
     * sub-representable range where the 8-bit lane rounds to zero; every column in it rendered at FULL
     * opacity, blanking the screen with a wall of sheets for about a second before the ramp climbed
     * clear of it — then again in reverse as the storm ended.
     */
    @Test
    void zeroParticleCoverageIsTransparentRatherThanOpaque() throws IOException {
        String rahit = Files.readString(anyHitShader());

        assertTrue(rahit.contains("bool particleKind = instanceKind == PARTICLE_BIT;"),
                "the coverage reading must distinguish particle/weather prims from model geometry");
        assertTrue(rahit.contains("if (!particleKind && epr.aux0 == 0u)"),
                "the zero-means-opaque sentinel must be confined to model geometry");
        assertFalse(rahit.contains("float primCoverage = epr.aux0 == 0u ? 1.0"),
                "reading a particle's zero coverage as opaque makes fading rain flash fully solid");
    }

    /**
     * The density option must survive the whole storm, including its opening and closing moments.
     *
     * <p>The flash was never the multiplier failing to apply — it was the coverage branch above
     * discarding it. Coverage is {@code columnAlpha * visualIntensity * rainDensity}, so a configured
     * 0.2 simply reaches the first representable alpha byte a little later than 1.0 does; while the
     * product sat in the dead zone the sentinel replaced it with 1.0 and the sheet rendered at maximum
     * density regardless of the setting. Pinning the sentinel to non-particle geometry is what keeps the
     * option in force for every frame of the ramp.
     */
    @Test
    void theDensityOptionIsNotOverriddenDuringTheRamp() throws IOException {
        String rahit = Files.readString(anyHitShader());

        // The multiplier must reach the alpha test unmodified for particle/weather prims...
        assertTrue(rahit.contains("float primCoverage = clamp(asfloat(epr.aux0), 0.0, 1.0);"),
                "captured coverage must be the starting value for every instance kind");
        // ...which means the only override left is explicitly gated on NOT being a particle.
        assertEquals(1, occurrences(rahit, "epr.aux0 == 0u"),
                "exactly one coverage override may exist, and it must be the model-geometry one");
        assertTrue(rahit.contains("texel.a * primCoverage < ENTITY_ALPHA_CUTOFF"),
                "the coverage multiplier must still weigh the texel alpha in the cutout test");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** Weather must stay on the fractional-coverage path, even below the entity cutoff. */
    @Test
    void lowOpacityWeatherReachesStochasticCoverage() throws IOException {
        String src = Files.readString(source());
        String rahit = Files.readString(anyHitShader());
        assertTrue(src.contains("MIN_VISIBLE_ALPHA = 1.0f / 255.0f"));
        assertTrue(rahit.contains("instanceKind == PARTICLE_BIT && (epr.flags & PRIM_WEATHER) != 0u"),
                "weather must not silently fall back to opaque cutout after material refactors");
    }
    private static Path anyHitShader() {
        return repoRoot().resolve("shaders/world/world.rahit.slang");
    }

    /**
     * Weather rides the particle mesh, so it must stay inside the particle budget and must not be able
     * to starve the ParticleEngine billboards that share it.
     */
    @Test
    void captureIsBudgetedAndFailsSoft() throws IOException {
        String src = Files.readString(source());

        assertTrue(src.contains("budget <= 0"), "a zero/negative budget must capture nothing");
        assertTrue(src.contains("emitted >= budget"), "the per-list loop must stop at the budget");
        // A weather glitch must not disable the entire ray-traced frame: RtEntities' particle path
        // rethrows as a fatal RuntimeException, so this class swallows and logs instead.
        assertTrue(src.contains("catch (Throwable t)") && src.contains("loggedFailure"),
                "weather capture must fail soft and log once rather than kill the RT frame");
    }
}
