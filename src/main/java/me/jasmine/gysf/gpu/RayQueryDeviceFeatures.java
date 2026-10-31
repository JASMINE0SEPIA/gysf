package me.jasmine.gysf.gpu;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;

/** Startup-only prerequisites; Minecraft owns support checks, native structs and device creation. */
public final class RayQueryDeviceFeatures {
    private static final Logger LOGGER = LoggerFactory.getLogger("gysf");
    private static final FeatureSet OPTIONAL = new FeatureSet(
        "gysf local ray query",
        Set.of(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME, VK_KHR_RAY_QUERY_EXTENSION_NAME),
        Set.of(
            // Minecraft requires Vulkan 1.2: reuse its core struct, including its existing feature bits.
            new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress"),
            new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class),
                "accelerationStructure"),
            new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class), "rayQuery")
        )
    );

    private RayQueryDeviceFeatures() {}

    public static Set<FeatureSet> includeIn(Set<FeatureSet> hostOptions) {
        if (!Boolean.parseBoolean(System.getProperty("gysf.gpu.localRayQuery", "true"))) {
            LOGGER.info("Local ray query device request disabled by startup option");
            return hostOptions;
        }

        // Do not mutate Minecraft's set. Its normal negotiation accepts or rejects the entire group.
        Set<FeatureSet> options = new HashSet<>(hostOptions);
        options.add(OPTIONAL);
        return options;
    }
}
