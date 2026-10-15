#include "vulkan_clear_sync.h"
#include <stdexcept>
#include <vector>

static std::vector<int> events;
static VkCommandBuffer expectedCommand=reinterpret_cast<VkCommandBuffer>(0x1000);
static VkImage expectedImage=(VkImage)0x2000;
static VkClearColorValue expectedColor{{.1f,.2f,.3f,.4f}};
static VkImageSubresourceRange expectedRange{VK_IMAGE_ASPECT_COLOR_BIT,2,3,4,5};
static void require(bool condition) {if(!condition)throw std::runtime_error("NGX clear synchronization contract failed");}
static VKAPI_ATTR void VKAPI_CALL barrier(VkCommandBuffer cmd,VkPipelineStageFlags src,VkPipelineStageFlags dst,
        VkDependencyFlags flags,uint32_t count,const VkMemoryBarrier* memory,uint32_t buffers,
        const VkBufferMemoryBarrier* buffer,uint32_t images,const VkImageMemoryBarrier* image) {
    require(cmd==expectedCommand && src==VK_PIPELINE_STAGE_ALL_COMMANDS_BIT && dst==VK_PIPELINE_STAGE_TRANSFER_BIT);
    require(flags==0 && count==1 && buffers==0 && buffer==nullptr && images==0 && image==nullptr);
    require(memory->sType==VK_STRUCTURE_TYPE_MEMORY_BARRIER && memory->pNext==nullptr);
    require(memory->srcAccessMask==VK_ACCESS_MEMORY_WRITE_BIT && memory->dstAccessMask==VK_ACCESS_TRANSFER_WRITE_BIT);
    events.push_back(1);
}
static VKAPI_ATTR void VKAPI_CALL clear(VkCommandBuffer cmd,VkImage image,VkImageLayout layout,
        const VkClearColorValue* color,uint32_t count,const VkImageSubresourceRange* ranges) {
    require(cmd==expectedCommand && image==expectedImage && layout==VK_IMAGE_LAYOUT_GENERAL);
    require(color==&expectedColor && count==1 && ranges==&expectedRange);
    events.push_back(2);
}
static VKAPI_ATTR void VKAPI_CALL unrelated() {}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL device(VkDevice,const char* name) {
    if(std::strcmp(name,"vkCmdPipelineBarrier")==0)return reinterpret_cast<PFN_vkVoidFunction>(barrier);
    if(std::strcmp(name,"vkCmdClearColorImage")==0)return reinterpret_cast<PFN_vkVoidFunction>(clear);
    if(std::strcmp(name,"vkGetDeviceProcAddr")==0)return reinterpret_cast<PFN_vkVoidFunction>(device);
    if(std::strcmp(name,"unsupported")==0)return nullptr;
    return unrelated;
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL instance(VkInstance,const char* name) {return device(VK_NULL_HANDLE,name);}
int main() {
    require(!ngxshim_vulkan::bind(VK_NULL_HANDLE,instance,nullptr));
    require(ngxshim_vulkan::bind(VK_NULL_HANDLE,nullptr,device));
    auto nullable=reinterpret_cast<PFN_vkCmdClearColorImage>(ngxshim_vulkan::deviceProc(VK_NULL_HANDLE,"vkCmdClearColorImage"));
    nullable(expectedCommand,expectedImage,VK_IMAGE_LAYOUT_GENERAL,&expectedColor,1,&expectedRange);
    require(events==std::vector<int>({1,2}));events.clear();
    require(ngxshim_vulkan::bind(VK_NULL_HANDLE,instance,device));
    auto invoke=[](PFN_vkVoidFunction p) {reinterpret_cast<PFN_vkCmdClearColorImage>(p)
            (expectedCommand,expectedImage,VK_IMAGE_LAYOUT_GENERAL,&expectedColor,1,&expectedRange);};
    invoke(ngxshim_vulkan::deviceProc(VK_NULL_HANDLE,"vkCmdClearColorImage"));
    invoke(ngxshim_vulkan::instanceProc(VK_NULL_HANDLE,"vkCmdClearColorImage"));
    auto chained=reinterpret_cast<PFN_vkGetDeviceProcAddr>(ngxshim_vulkan::instanceProc(VK_NULL_HANDLE,"vkGetDeviceProcAddr"));
    invoke(chained(VK_NULL_HANDLE,"vkCmdClearColorImage"));
    require(events==std::vector<int>({1,2,1,2,1,2}) && ngxshim_vulkan::clearCount==3);
    require(ngxshim_vulkan::deviceProc(VK_NULL_HANDLE,"unrelated")==unrelated);
    require(ngxshim_vulkan::deviceProc(VK_NULL_HANDLE,"unsupported")==nullptr);
}
