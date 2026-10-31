package me.jasmine.gysf.integration.minecraft;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.TreeSet;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;

/** Opt-in, read-only observations at Minecraft's device and rendering boundaries. */
public final class GpuDiagnostics {
    public static final boolean ENABLED = Boolean.getBoolean("gysf.debug.gpuProbe");
    private static final Logger LOGGER = LoggerFactory.getLogger("gysf");

    private GpuDiagnostics() {}

    public static void reportDevice(VkPhysicalDevice device, VkDeviceCreateInfo createInfo) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Set<String> available = VulkanUtils.enumerateExtensions(device);
            Set<String> enabledExtensions = extensionNames(createInfo.ppEnabledExtensionNames());
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device, properties);
            int version = properties.apiVersion();
            LOGGER.info("GPU probe device={} api={}.{}.{}", properties.deviceNameString(),
                VK_VERSION_MAJOR(version), VK_VERSION_MINOR(version), VK_VERSION_PATCH(version));
            LOGGER.info("GPU probe enabled extensions={}", enabledExtensions);

            // Minecraft 26.3 requires Vulkan 1.2. Only query extension feature structs advertised by this device.
            VkPhysicalDeviceVulkan12Features core = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 supported = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(core.address());
            VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration = null;
            if (available.contains(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)) {
                acceleration = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().pNext(supported.pNext());
                supported.pNext(acceleration.address());
            }
            VkPhysicalDeviceRayQueryFeaturesKHR rayQuery = null;
            if (available.contains(VK_KHR_RAY_QUERY_EXTENSION_NAME)) {
                rayQuery = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default().pNext(supported.pNext());
                supported.pNext(rayQuery.address());
            }
            vkGetPhysicalDeviceFeatures2(device, supported);

            Features enabled = readEnabledFeatures(createInfo.pNext());
            logFeature("bufferDeviceAddress", core.bufferDeviceAddress(), enabled.bufferDeviceAddress());
            boolean accelerationEnabled = enabled.accelerationStructure()
                && enabledExtensions.contains(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
            boolean rayQueryEnabled = enabled.rayQuery() && enabledExtensions.contains(VK_KHR_RAY_QUERY_EXTENSION_NAME);
            logFeature("accelerationStructure", acceleration != null && acceleration.accelerationStructure(), accelerationEnabled);
            logFeature("rayQuery", rayQuery != null && rayQuery.rayQuery(), rayQueryEnabled);
            boolean deferredEnabled = enabledExtensions.contains(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
            logFeature("deferredHostOperations extension", available.contains(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME), deferredEnabled);
            LOGGER.info("GPU probe local ray-query device prerequisites enabled={}",
                enabled.bufferDeviceAddress() && accelerationEnabled && rayQueryEnabled && deferredEnabled);
            reportQueues(device, createInfo, stack);
        } catch (BackendCreationException | RuntimeException exception) {
            // A diagnostic failure must not turn a successfully created host device into a startup failure.
            LOGGER.warn("GPU probe failed; no capability conclusion is available", exception);
        }
    }

    private static Features readEnabledFeatures(long chain) {
        boolean address = false;
        boolean acceleration = false;
        boolean rayQuery = false;
        for (VkBaseInStructure node = VkBaseInStructure.createSafe(chain); node != null;
             node = node.pNext()) {
            long pointer = node.address();
            switch (node.sType()) {
                case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES ->
                    address = VkPhysicalDeviceVulkan12Features.create(pointer).bufferDeviceAddress();
                case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR ->
                    address = VkPhysicalDeviceBufferDeviceAddressFeatures.create(pointer).bufferDeviceAddress();
                case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR ->
                    acceleration = VkPhysicalDeviceAccelerationStructureFeaturesKHR.create(pointer).accelerationStructure();
                case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR ->
                    rayQuery = VkPhysicalDeviceRayQueryFeaturesKHR.create(pointer).rayQuery();
                default -> { }
            }
        }
        return new Features(address, acceleration, rayQuery);
    }

    private static Set<String> extensionNames(PointerBuffer names) {
        Set<String> result = new TreeSet<>();
        if (names != null) {
            for (int i = names.position(); i < names.limit(); i++) {
                result.add(MemoryUtil.memUTF8(names.get(i)));
            }
        }
        return result;
    }

    private static void reportQueues(VkPhysicalDevice device, VkDeviceCreateInfo info, MemoryStack stack) {
        var count = stack.callocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        var requests = info.pQueueCreateInfos();
        for (int i = requests.position(); i < requests.limit(); i++) {
            var request = requests.get(i);
            int flags = families.get(request.queueFamilyIndex()).queueFlags();
            LOGGER.info("GPU probe requested queue family={} count={} graphics={} compute={} transfer={}",
                request.queueFamilyIndex(), request.queueCount(), (flags & VK_QUEUE_GRAPHICS_BIT) != 0,
                (flags & VK_QUEUE_COMPUTE_BIT) != 0, (flags & VK_QUEUE_TRANSFER_BIT) != 0);
        }
    }

    private static void logFeature(String name, boolean supported, boolean enabled) {
        LOGGER.info("GPU probe {} supported={} enabled={}", name, supported, enabled);
    }

    public static void reportTarget(String stage, RenderTarget target) {
        RenderSystem.assertOnRenderThread();
        var color = target.getColorTexture();
        var depth = target.getDepthTexture();
        LOGGER.info("GPU probe target stage={} size={}x{} color={} depth={}", stage, target.width, target.height,
            color == null ? "absent" : color.getFormat(), depth == null ? "absent" : depth.getFormat());
        LOGGER.info("GPU probe target views color={} depth={} borrowed=true",
            target.getColorTextureView() != null, target.getDepthTextureView() != null);
    }

    private record Features(boolean bufferDeviceAddress, boolean accelerationStructure, boolean rayQuery) {}
}
