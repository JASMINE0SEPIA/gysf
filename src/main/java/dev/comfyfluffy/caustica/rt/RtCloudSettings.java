package dev.comfyfluffy.caustica.rt;

import net.minecraft.client.CloudStatus;

/** Shared visible/shadow cloud policy, including Minecraft's own video setting. */
final class RtCloudSettings {
    private RtCloudSettings() {}
    static boolean enabled(boolean configured, CloudStatus vanilla) {
        return configured && vanilla != CloudStatus.OFF;
    }
    static float thickness(boolean volumetric, CloudStatus vanilla, float amount) {
        if (vanilla == CloudStatus.OFF) return 0f;
        // Classic follows vanilla's flat FAST sheet / four-block FANCY boxes. A volumetric
        // depth slider must not extrude the authored classic cells into 55-block towers.
        if (!volumetric) return vanilla == CloudStatus.FAST ? 0f : 4f;
        return Math.clamp(amount, 0f, 1f) * 110f;
    }
}
