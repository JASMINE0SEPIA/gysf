#include <vulkan/vulkan.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

static void check(VkResult r,const char* operation) {
    if(r!=VK_SUCCESS) throw std::runtime_error(std::string(operation)+": "+std::to_string(r));
}
struct Buffer { VkBuffer handle{};VkDeviceMemory memory{};VkDeviceSize bytes{};void* mapped{};bool coherent{}; };
struct Result { float t,residual,work,exhausted; };
struct Push { uint32_t count,shuffled,reserved0{},reserved1{}; };
struct Correctness { uint32_t failures{},exhausted{},maxWork{};double workSum{};float maxResidual{}; };
struct Run { std::string variant;bool shuffled;Correctness correctness;std::vector<double> milliseconds; };

struct Runner {
    VkInstance instance{};VkDebugUtilsMessengerEXT messenger{};VkPhysicalDevice physical{};VkDevice device{};VkQueue queue{};
    VkPhysicalDeviceProperties properties{};VkPhysicalDeviceDriverProperties driver{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
    VkPhysicalDeviceMemoryProperties memoryProperties{};uint32_t family{},timestampBits{};
    VkCommandPool commandPool{};VkCommandBuffer command{};VkFence fence{};VkQueryPool queries{};
    VkDescriptorSetLayout setLayout{};VkDescriptorPool descriptorPool{};VkDescriptorSet descriptor{};VkPipelineLayout layout{};
    std::array<VkPipeline,2> pipelines{};Buffer output,domains,staging;std::atomic_uint errors{};
    bool tracing{};VkPipelineStageFlags shaderStages=VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    ~Runner(){close();}
    static VKAPI_ATTR VkBool32 VKAPI_CALL debug(VkDebugUtilsMessageSeverityFlagBitsEXT severity,VkDebugUtilsMessageTypeFlagsEXT,
            const VkDebugUtilsMessengerCallbackDataEXT* data,void* user) {
        if(severity&VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) static_cast<Runner*>(user)->errors++;
        std::cerr<<"VULKAN_VALIDATION: "<<data->pMessage<<'\n';return VK_FALSE;
    }
    void init(uint32_t count,const std::string& domainFile,const std::array<std::string,2>& shaders,bool trace=false) {
        tracing=trace;if(trace)shaderStages=VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        const char* layer="VK_LAYER_KHRONOS_validation";
        uint32_t layerCount=0;check(vkEnumerateInstanceLayerProperties(&layerCount,nullptr),"enumerate layers");
        std::vector<VkLayerProperties> layers(layerCount);check(vkEnumerateInstanceLayerProperties(&layerCount,layers.data()),"read layers");
        if(std::none_of(layers.begin(),layers.end(),[&](auto& p){return std::strcmp(p.layerName,layer)==0;})) throw std::runtime_error("Khronos validation layer unavailable");
        VkValidationFeatureEnableEXT enabled=VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT;
        VkValidationFeaturesEXT validation{VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT};validation.enabledValidationFeatureCount=1;validation.pEnabledValidationFeatures=&enabled;
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="gysf water equation probe";app.apiVersion=VK_API_VERSION_1_2;
        const char* extensions[]={VK_EXT_DEBUG_UTILS_EXTENSION_NAME,VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME};
        VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};create.pNext=&validation;create.pApplicationInfo=&app;
        create.enabledLayerCount=1;create.ppEnabledLayerNames=&layer;create.enabledExtensionCount=2;create.ppEnabledExtensionNames=extensions;
        check(vkCreateInstance(&create,nullptr,&instance),"create instance");
        VkDebugUtilsMessengerCreateInfoEXT debugInfo{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
        debugInfo.messageSeverity=VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
        debugInfo.messageType=VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
        debugInfo.pfnUserCallback=debug;debugInfo.pUserData=this;
        auto createDebug=(PFN_vkCreateDebugUtilsMessengerEXT)vkGetInstanceProcAddr(instance,"vkCreateDebugUtilsMessengerEXT");
        check(createDebug(instance,&debugInfo,nullptr,&messenger),"create debug messenger");
        uint32_t devices=0;check(vkEnumeratePhysicalDevices(instance,&devices,nullptr),"enumerate devices");
        std::vector<VkPhysicalDevice> candidates(devices);check(vkEnumeratePhysicalDevices(instance,&devices,candidates.data()),"read devices");
        for(auto candidate:candidates) {
            VkPhysicalDeviceProperties p{};vkGetPhysicalDeviceProperties(candidate,&p);
            if(p.deviceType==VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU){physical=candidate;break;}
        }
        if(!physical) throw std::runtime_error("No discrete GPU selected");
        VkPhysicalDeviceProperties2 properties2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};properties2.pNext=&driver;
        vkGetPhysicalDeviceProperties2(physical,&properties2);properties=properties2.properties;
        vkGetPhysicalDeviceMemoryProperties(physical,&memoryProperties);
        uint32_t families=0;vkGetPhysicalDeviceQueueFamilyProperties(physical,&families,nullptr);
        std::vector<VkQueueFamilyProperties> queues(families);vkGetPhysicalDeviceQueueFamilyProperties(physical,&families,queues.data());
        bool found=false;
        for(uint32_t i=0;i<families;i++) if((queues[i].queueFlags&VK_QUEUE_COMPUTE_BIT)&&queues[i].timestampValidBits){family=i;timestampBits=queues[i].timestampValidBits;found=true;break;}
        if(!found||properties.limits.timestampPeriod<=0||count/64>properties.limits.maxComputeWorkGroupCount[0]) throw std::runtime_error("Unsupported timestamp/dispatch limits");
        VkPhysicalDeviceFeatures available{};vkGetPhysicalDeviceFeatures(physical,&available);
        if(!available.shaderInt64) throw std::runtime_error("shaderInt64 unavailable");
        VkPhysicalDeviceFeatures features{};features.shaderInt64=VK_TRUE;
        float priority=1;VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};queueInfo.queueFamilyIndex=family;queueInfo.queueCount=1;queueInfo.pQueuePriorities=&priority;
        VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};deviceInfo.queueCreateInfoCount=1;deviceInfo.pQueueCreateInfos=&queueInfo;deviceInfo.pEnabledFeatures=&features;
        VkPhysicalDeviceBufferDeviceAddressFeatures bda{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES};
        VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR};
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayPipeline{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR};
        const char* rayExtensions[]={VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME};
        if(trace) {
            uint32_t extensionCount=0;check(vkEnumerateDeviceExtensionProperties(physical,nullptr,&extensionCount,nullptr),"enumerate device extensions");
            std::vector<VkExtensionProperties> supported(extensionCount);check(vkEnumerateDeviceExtensionProperties(physical,nullptr,&extensionCount,supported.data()),"read device extensions");
            for(auto required:rayExtensions)if(std::none_of(supported.begin(),supported.end(),[&](auto& x){return std::strcmp(x.extensionName,required)==0;}))throw std::runtime_error(std::string("Missing RT extension: ")+required);
            bda.pNext=&acceleration;acceleration.pNext=&rayPipeline;
            VkPhysicalDeviceFeatures2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};query.pNext=&bda;vkGetPhysicalDeviceFeatures2(physical,&query);
            if(!bda.bufferDeviceAddress||!acceleration.accelerationStructure||!rayPipeline.rayTracingPipeline)throw std::runtime_error("Required RT features unavailable");
            bda={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES};bda.bufferDeviceAddress=VK_TRUE;bda.pNext=&acceleration;
            acceleration={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR};acceleration.accelerationStructure=VK_TRUE;acceleration.pNext=&rayPipeline;
            rayPipeline={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR};rayPipeline.rayTracingPipeline=VK_TRUE;
            deviceInfo.pNext=&bda;deviceInfo.enabledExtensionCount=3;deviceInfo.ppEnabledExtensionNames=rayExtensions;
        }
        check(vkCreateDevice(physical,&deviceInfo,nullptr,&device),"create device");vkGetDeviceQueue(device,family,0,&queue);
        createBuffer(output,VkDeviceSize(count)*sizeof(Result),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        createBuffer(domains,7*8*sizeof(float),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        createBuffer(staging,output.bytes,VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        check(vkMapMemory(device,staging.memory,0,VK_WHOLE_SIZE,0,&staging.mapped),"map staging");
        std::ifstream input(domainFile);std::array<float,56> data{};for(auto& value:data) if(!(input>>value)) throw std::runtime_error("Incomplete production domain table");
        std::memcpy(staging.mapped,data.data(),sizeof(data));flush(false);
        VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};poolInfo.queueFamilyIndex=family;poolInfo.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device,&poolInfo,nullptr,&commandPool),"create command pool");
        VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};allocate.commandPool=commandPool;allocate.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;allocate.commandBufferCount=1;
        check(vkAllocateCommandBuffers(device,&allocate,&command),"allocate command");
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};check(vkCreateFence(device,&fenceInfo,nullptr,&fence),"create fence");
        VkQueryPoolCreateInfo queryInfo{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};queryInfo.queryType=VK_QUERY_TYPE_TIMESTAMP;queryInfo.queryCount=2;
        check(vkCreateQueryPool(device,&queryInfo,nullptr,&queries),"create queries");
        begin();VkBufferCopy copy{0,0,domains.bytes};vkCmdCopyBuffer(command,staging.handle,domains.handle,1,&copy);
        barrier(domains,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,shaderStages);submit();
        std::array<VkDescriptorSetLayoutBinding,3> bindings{};
        auto stages=trace?VK_SHADER_STAGE_RAYGEN_BIT_KHR|VK_SHADER_STAGE_INTERSECTION_BIT_KHR:VK_SHADER_STAGE_COMPUTE_BIT;
        for(int i=0;i<2;i++){bindings[i].binding=100+i;bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;bindings[i].descriptorCount=1;bindings[i].stageFlags=stages;}
        bindings[2]={102,VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,1,VK_SHADER_STAGE_RAYGEN_BIT_KHR,nullptr};
        VkDescriptorSetLayoutCreateInfo setInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};setInfo.bindingCount=trace?3:2;setInfo.pBindings=bindings.data();
        check(vkCreateDescriptorSetLayout(device,&setInfo,nullptr,&setLayout),"create descriptor layout");
        std::array<VkDescriptorPoolSize,2> poolSizes={VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,2},VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,1}};
        VkDescriptorPoolCreateInfo descriptorInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};descriptorInfo.maxSets=1;descriptorInfo.poolSizeCount=trace?2:1;descriptorInfo.pPoolSizes=poolSizes.data();
        check(vkCreateDescriptorPool(device,&descriptorInfo,nullptr,&descriptorPool),"create descriptor pool");
        VkDescriptorSetAllocateInfo setAllocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};setAllocate.descriptorPool=descriptorPool;setAllocate.descriptorSetCount=1;setAllocate.pSetLayouts=&setLayout;
        check(vkAllocateDescriptorSets(device,&setAllocate,&descriptor),"allocate descriptor");
        std::array<VkDescriptorBufferInfo,2> infos={VkDescriptorBufferInfo{output.handle,0,output.bytes},VkDescriptorBufferInfo{domains.handle,0,domains.bytes}};
        std::array<VkWriteDescriptorSet,2> writes{};
        for(int i=0;i<2;i++){writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;writes[i].dstSet=descriptor;writes[i].dstBinding=100+i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&infos[i];}
        vkUpdateDescriptorSets(device,2,writes.data(),0,nullptr);
        VkPushConstantRange range{VkShaderStageFlags(stages),0,sizeof(Push)};
        VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};layoutInfo.setLayoutCount=1;layoutInfo.pSetLayouts=&setLayout;layoutInfo.pushConstantRangeCount=1;layoutInfo.pPushConstantRanges=&range;
        check(vkCreatePipelineLayout(device,&layoutInfo,nullptr,&layout),"create pipeline layout");
        for(int i=0;i<2 && !trace;i++) {
            std::ifstream spv(shaders[i],std::ios::binary|std::ios::ate);if(!spv) throw std::runtime_error("SPIR-V file missing");
            auto bytes=spv.tellg();if(bytes<=0||bytes%4) throw std::runtime_error("Invalid SPIR-V size");
            std::vector<uint32_t> words(size_t(bytes)/4);spv.seekg(0);spv.read((char*)words.data(),bytes);
            VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};moduleInfo.codeSize=size_t(bytes);moduleInfo.pCode=words.data();VkShaderModule module{};
            check(vkCreateShaderModule(device,&moduleInfo,nullptr,&module),"create shader module");
            VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};pipelineInfo.layout=layout;pipelineInfo.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;pipelineInfo.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;pipelineInfo.stage.module=module;pipelineInfo.stage.pName="main";
            VkResult status=vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&pipelineInfo,nullptr,&pipelines[i]);vkDestroyShaderModule(device,module,nullptr);check(status,"create compute pipeline");
        }
    }
    void createBuffer(Buffer& b,VkDeviceSize bytes,VkBufferUsageFlags usage,VkMemoryPropertyFlags required) {
        b.bytes=bytes;VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};info.size=bytes;info.usage=usage;info.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateBuffer(device,&info,nullptr,&b.handle),"create buffer");VkMemoryRequirements requirements{};vkGetBufferMemoryRequirements(device,b.handle,&requirements);
        uint32_t type=UINT32_MAX;
        for(uint32_t i=0;i<memoryProperties.memoryTypeCount;i++) if((requirements.memoryTypeBits&(1u<<i))&&(memoryProperties.memoryTypes[i].propertyFlags&required)==required) {
            if(type==UINT32_MAX) type=i;
            if(!(required&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)||(memoryProperties.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)){type=i;break;}
        }
        if(type==UINT32_MAX) throw std::runtime_error("No suitable buffer memory");
        b.coherent=(memoryProperties.memoryTypes[type].propertyFlags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)!=0;
        VkMemoryAllocateFlagsInfo addressFlags{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO};addressFlags.flags=VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};allocation.allocationSize=requirements.size;allocation.memoryTypeIndex=type;
        if(usage&VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)allocation.pNext=&addressFlags;
        check(vkAllocateMemory(device,&allocation,nullptr,&b.memory),"allocate buffer memory");check(vkBindBufferMemory(device,b.handle,b.memory,0),"bind buffer memory");
    }
    void flush(bool invalidate) {
        if(staging.coherent)return;VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};range.memory=staging.memory;range.size=VK_WHOLE_SIZE;
        check(invalidate?vkInvalidateMappedMemoryRanges(device,1,&range):vkFlushMappedMemoryRanges(device,1,&range),"staging visibility");
    }
    void begin(){check(vkResetCommandBuffer(command,0),"reset command");VkCommandBufferBeginInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};check(vkBeginCommandBuffer(command,&info),"begin command");}
    void barrier(const Buffer& b,VkAccessFlags src,VkAccessFlags dst,VkPipelineStageFlags from,VkPipelineStageFlags to) {
        VkBufferMemoryBarrier memory{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};memory.srcAccessMask=src;memory.dstAccessMask=dst;memory.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;memory.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;memory.buffer=b.handle;memory.size=VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(command,from,to,0,0,nullptr,1,&memory,0,nullptr);
    }
    void submit(){check(vkEndCommandBuffer(command),"end command");check(vkResetFences(device,1,&fence),"reset fence");VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};submitInfo.commandBufferCount=1;submitInfo.pCommandBuffers=&command;check(vkQueueSubmit(queue,1,&submitInfo,fence),"submit");check(vkWaitForFences(device,1,&fence,VK_TRUE,60'000'000'000ull),"wait GPU");}
    double dispatch(uint32_t variant,uint32_t count,bool shuffled) {
        begin();barrier(output,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        vkCmdResetQueryPool(command,queries,0,2);vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipelines[variant]);
        vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&descriptor,0,nullptr);Push push{count,uint32_t(shuffled)};
        vkCmdPushConstants(command,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(push),&push);
        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0);vkCmdDispatch(command,count/64,1,1);vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1);submit();
        uint64_t times[2]{};check(vkGetQueryPoolResults(device,queries,0,2,sizeof(times),times,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT),"read timestamps");
        uint64_t mask=timestampBits==64?UINT64_MAX:((uint64_t(1)<<timestampBits)-1);
        return double((times[1]-times[0])&mask)*properties.limits.timestampPeriod/1e6;
    }
    Correctness readback(uint32_t count,const std::filesystem::path& file) {
        begin();barrier(output,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,shaderStages,VK_PIPELINE_STAGE_TRANSFER_BIT);
        barrier(staging,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_HOST_READ_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferCopy copy{0,0,output.bytes};vkCmdCopyBuffer(command,output.handle,staging.handle,1,&copy);
        barrier(staging,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT);submit();flush(true);
        auto data=static_cast<Result*>(staging.mapped);Correctness result{};std::ofstream csv(file);csv<<"index,hit_t,residual,work,exhausted\n"<<std::setprecision(9);
        for(uint32_t i=0;i<count;i++) {
            auto r=data[i];if(!std::isfinite(r.residual)||!std::isfinite(r.t)||r.residual>0.001f)result.failures++;
            if(r.exhausted!=0) result.exhausted++;
            if(!std::isfinite(r.work)||r.work<0||r.work>512) result.failures++;
            else {result.maxWork=std::max(result.maxWork,uint32_t(r.work));result.workSum+=r.work;}
            result.maxResidual=std::max(result.maxResidual,r.residual);
            if(i<4096)csv<<i<<','<<r.t<<','<<r.residual<<','<<r.work<<','<<r.exhausted<<'\n';
        }
        return result;
    }
    void close() {
        if(device){vkDeviceWaitIdle(device);for(auto p:pipelines)if(p)vkDestroyPipeline(device,p,nullptr);pipelines={};
            if(layout)vkDestroyPipelineLayout(device,layout,nullptr);if(descriptorPool)vkDestroyDescriptorPool(device,descriptorPool,nullptr);if(setLayout)vkDestroyDescriptorSetLayout(device,setLayout,nullptr);
            if(queries)vkDestroyQueryPool(device,queries,nullptr);if(commandPool)vkDestroyCommandPool(device,commandPool,nullptr);if(fence)vkDestroyFence(device,fence,nullptr);
            for(Buffer* b:{&output,&domains,&staging}){if(b->mapped)vkUnmapMemory(device,b->memory);if(b->handle)vkDestroyBuffer(device,b->handle,nullptr);if(b->memory)vkFreeMemory(device,b->memory,nullptr);*b={};}
            vkDestroyDevice(device,nullptr);device={};}
        if(messenger){auto destroy=(PFN_vkDestroyDebugUtilsMessengerEXT)vkGetInstanceProcAddr(instance,"vkDestroyDebugUtilsMessengerEXT");destroy(instance,messenger,nullptr);messenger={};}
        if(instance){vkDestroyInstance(instance,nullptr);instance={};}
    }
};

#ifndef GYSF_WATER_GPU_LIBRARY
int main(int argc,char** argv) try {
    if(argc!=6 && argc!=7) throw std::runtime_error("Usage: water_gpu legacy.spv candidate.spv domain.txt output-directory ray-count [grazing|finite-patch]");
    std::string kind=argc==7?argv[6]:"grazing";
    if(kind!="grazing" && kind!="finite-patch")throw std::runtime_error("Unknown probe kind");
    uint32_t count=std::stoul(argv[5]);if(count<4096||count%4096||count>4'194'240) throw std::runtime_error("Ray count must be a bounded multiple of 4096");
    std::filesystem::path directory(argv[4]);std::filesystem::create_directories(directory);if(std::filesystem::exists(directory/"results.json"))throw std::runtime_error("Use a fresh output directory");
    Runner gpu;gpu.init(count,argv[3],{argv[1],argv[2]});std::vector<Run> runs;
    for(bool shuffled:{false,true}) {
        std::array<Run,2> pair={Run{"production",shuffled},Run{"candidate",shuffled}};
        for(uint32_t v=0;v<2;v++){gpu.dispatch(v,count,shuffled);pair[v].correctness=gpu.readback(count,directory/(pair[v].variant+(shuffled?"-shuffled.csv":"-coherent.csv")));}
        for(int warm=0;warm<6;warm++){gpu.dispatch(warm&1,count,shuffled);}
        // ABBA order reduces one-direction thermal/clock drift. Samples retain the measured order.
        for(int sample=0;sample<20;sample++) for(int order=0;order<2;order++){uint32_t v=(sample&1)?1-order:order;pair[v].milliseconds.push_back(gpu.dispatch(v,count,shuffled));}
        for(auto& run:pair)runs.push_back(std::move(run));
    }
    auto name=std::string(gpu.properties.deviceName),driver=std::string(gpu.driver.driverInfo);float period=gpu.properties.limits.timestampPeriod;gpu.close();
    std::ofstream json(directory/"results.json");json<<std::setprecision(9)<<"{\n  \"probe\": "<<std::quoted(kind)<<",\n  \"gpu\": "<<std::quoted(name)<<",\n  \"driver\": "<<std::quoted(driver)<<",\n  \"raysPerDispatch\": "<<count<<",\n  \"timestampPeriodNs\": "<<period<<",\n  \"validationErrors\": "<<gpu.errors.load()<<",\n  \"runs\": [\n";
    bool correct=gpu.errors==0;
    for(size_t i=0;i<runs.size();i++) {
        auto& r=runs[i];auto sorted=r.milliseconds;std::sort(sorted.begin(),sorted.end());double median=(sorted[9]+sorted[10])*0.5;
        auto& c=r.correctness;bool candidate=r.variant=="candidate";if(candidate&&(c.failures||c.exhausted))correct=false;
        std::cout<<"WATER_GPU "<<r.variant<<" shuffled="<<r.shuffled<<" medianMs="<<median<<" maxMs="<<sorted.back()<<" residualFailures="<<c.failures<<" maxResidual="<<c.maxResidual<<" exhausted="<<c.exhausted<<'\n';
        json<<"    {\"variant\": "<<std::quoted(r.variant)<<", \"shuffled\": "<<(r.shuffled?"true":"false")<<", \"residualFailures\": "<<c.failures<<", \"maxResidualBlocks\": "<<c.maxResidual<<", \"exhaustions\": "<<c.exhausted<<", \"meanWalkEvaluations\": "<<c.workSum/count<<", \"maxWalkEvaluations\": "<<c.maxWork<<", \"medianMs\": "<<median<<", \"samplesMs\": [";
        for(size_t j=0;j<r.milliseconds.size();j++)json<<(j?", ":"")<<r.milliseconds[j];json<<"]}"<<(i+1<runs.size()?",":"")<<'\n';
    }
    json<<"  ],\n  \"limits\": \"Synthetic compute equation kernels; no ray traversal, denoising or whole-frame timing.\"\n}\n";
    return correct?0:1;
} catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 2;}
#endif
