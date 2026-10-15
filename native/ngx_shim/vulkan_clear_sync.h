#pragma once
#include <vulkan/vulkan.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// Only NGX receives these proc callbacks. The app/driver dispatch tables are untouched.
// DLSSD 310.9.1 transitions internal images for shader access and then clears them without a
// transfer-write dependency. Publish preceding writes/layout transitions before each NGX clear.
namespace ngxshim_vulkan {
inline PFN_vkGetInstanceProcAddr originalInstanceProc=nullptr;
inline PFN_vkGetDeviceProcAddr originalDeviceProc=nullptr;
inline PFN_vkCmdPipelineBarrier originalBarrier=nullptr;
inline PFN_vkCmdClearColorImage originalClear=nullptr;
inline std::atomic<unsigned long long> clearCount{0};

inline bool bind(VkDevice device,PFN_vkGetInstanceProcAddr instanceProc,PFN_vkGetDeviceProcAddr deviceProc) {
    originalInstanceProc=instanceProc;originalDeviceProc=deviceProc;
    if(!deviceProc)return false; // Java/NGX already support a null instance resolver.
    originalBarrier=reinterpret_cast<PFN_vkCmdPipelineBarrier>(deviceProc(device,"vkCmdPipelineBarrier"));
    originalClear=reinterpret_cast<PFN_vkCmdClearColorImage>(deviceProc(device,"vkCmdClearColorImage"));
    clearCount=0;
    return originalBarrier && originalClear;
}

inline VKAPI_ATTR void VKAPI_CALL synchronizedClear(VkCommandBuffer command,VkImage image,VkImageLayout layout,
        const VkClearColorValue* color,uint32_t rangeCount,const VkImageSubresourceRange* ranges) {
    VkMemoryBarrier dependency{};
    dependency.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    dependency.srcAccessMask=VK_ACCESS_MEMORY_WRITE_BIT;
    dependency.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
    originalBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,1,&dependency,0,nullptr,0,nullptr);
    auto count=++clearCount;
    if(std::getenv("NGXSHIM_SYNC_TRACE") && count<=8) {
        std::fprintf(stderr,"[ngx_shim] Vulkan clear synchronized: call=%llu image=%p\n",count,(void*)image);
        std::fflush(stderr);
    }
    originalClear(command,image,layout,color,rangeCount,ranges);
}

inline VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL instanceProc(VkInstance instance,const char* name);
inline VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL deviceProc(VkDevice device,const char* name);
inline PFN_vkVoidFunction route(const char* name,PFN_vkVoidFunction original) {
    if(std::getenv("NGXSHIM_SYNC_TRACE") && std::strcmp(name,"vkCmdClearColorImage")==0) {
        std::fprintf(stderr,"[ngx_shim] NGX clear function resolved: original=%p\n",(void*)original);
        std::fflush(stderr);
    }
    if(!original)return nullptr; // retain unsupported-function lookup semantics
    if(std::strcmp(name,"vkCmdClearColorImage")==0)return reinterpret_cast<PFN_vkVoidFunction>(synchronizedClear);
    if(std::strcmp(name,"vkGetDeviceProcAddr")==0)return reinterpret_cast<PFN_vkVoidFunction>(deviceProc);
    if(originalInstanceProc && std::strcmp(name,"vkGetInstanceProcAddr")==0)return reinterpret_cast<PFN_vkVoidFunction>(instanceProc);
    return original;
}
inline VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL instanceProc(VkInstance instance,const char* name) {
    return originalInstanceProc ? route(name,originalInstanceProc(instance,name)) : nullptr;
}
inline VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL deviceProc(VkDevice device,const char* name) {
    return route(name,originalDeviceProc(device,name));
}
}
