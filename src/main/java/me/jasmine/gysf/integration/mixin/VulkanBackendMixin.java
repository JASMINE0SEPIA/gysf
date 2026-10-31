package me.jasmine.gysf.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import me.jasmine.gysf.integration.minecraft.GpuDiagnostics;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @WrapOperation(
        method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I")
    )
    private static int gysf$observeDeviceCreation(
        VkPhysicalDevice physicalDevice, VkDeviceCreateInfo createInfo,
        VkAllocationCallbacks allocator, PointerBuffer device, Operation<Integer> original
    ) {
        int result = original.call(physicalDevice, createInfo, allocator, device);
        if (result == VK_SUCCESS && GpuDiagnostics.ENABLED) {
            // Read the actual successful creation request while its stack data is still alive.
            GpuDiagnostics.reportDevice(physicalDevice, createInfo);
        }
        return result;
    }
}
