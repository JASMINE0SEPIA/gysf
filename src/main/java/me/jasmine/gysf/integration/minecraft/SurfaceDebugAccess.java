package me.jasmine.gysf.integration.minecraft;

import me.jasmine.gysf.render.terrain.TerrainSurfaceRenderer;

/** Narrow access to terrain rendering and its diagnostics, owned by this GameRenderer. */
public interface SurfaceDebugAccess {
    TerrainSurfaceRenderer gysf$surfaceDebug();
}
