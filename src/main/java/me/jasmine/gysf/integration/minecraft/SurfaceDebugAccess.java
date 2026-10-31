package me.jasmine.gysf.integration.minecraft;

import me.jasmine.gysf.debug.TerrainSurfaceDebug;

/** Narrow access to the debug resources owned by this GameRenderer. */
public interface SurfaceDebugAccess {
    TerrainSurfaceDebug gysf$surfaceDebug();
}
