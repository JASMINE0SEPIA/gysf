#define GYSF_WATER_GPU_LIBRARY
#include "water_gpu.cpp"

static VkDeviceSize aligned(VkDeviceSize value,VkDeviceSize alignment){return (value+alignment-1)/alignment*alignment;}
struct Acceleration { VkAccelerationStructureKHR handle{};Buffer storage,input; };

struct TraceRunner:Runner {
    PFN_vkCreateAccelerationStructureKHR createAS{};PFN_vkDestroyAccelerationStructureKHR destroyAS{};
    PFN_vkGetAccelerationStructureBuildSizesKHR getSizes{};PFN_vkGetAccelerationStructureDeviceAddressKHR getASAddress{};
    PFN_vkCmdBuildAccelerationStructuresKHR buildAS{};PFN_vkCreateRayTracingPipelinesKHR createPipelines{};
    PFN_vkGetRayTracingShaderGroupHandlesKHR getHandles{};PFN_vkCmdTraceRaysKHR traceRays{};
    VkPhysicalDeviceRayTracingPipelinePropertiesKHR rt{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR};
    VkPhysicalDeviceAccelerationStructurePropertiesKHR as{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR};
    std::array<Acceleration,8> bottoms{};Acceleration top;std::array<Buffer,2> sbts{};
    std::array<std::array<VkStridedDeviceAddressRegionKHR,3>,2> regions{};
    bool scene{};
    ~TraceRunner(){close();}
    VkDeviceAddress address(const Buffer& b){VkBufferDeviceAddressInfo info{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO};info.buffer=b.handle;auto value=vkGetBufferDeviceAddress(device,&info);if(!value)throw std::runtime_error("Zero buffer address");return value;}
    VkDeviceAddress accelerationAddress(const Acceleration& a){VkAccelerationStructureDeviceAddressInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR};info.accelerationStructure=a.handle;return getASAddress(device,&info);}
    void release(Buffer& b){if(b.mapped)vkUnmapMemory(device,b.memory);if(b.handle)vkDestroyBuffer(device,b.handle,nullptr);if(b.memory)vkFreeMemory(device,b.memory,nullptr);b={};}
    void upload(Buffer& b,const void* data,VkDeviceSize bytes,VkBufferUsageFlags usage) {
        createBuffer(b,bytes,usage|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        check(vkMapMemory(device,b.memory,0,VK_WHOLE_SIZE,0,&b.mapped),"map RT input");std::memcpy(b.mapped,data,size_t(bytes));
        visible(b);
    }
    void visible(Buffer& b){if(!b.coherent){VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};range.memory=b.memory;range.size=VK_WHOLE_SIZE;check(vkFlushMappedMemoryRanges(device,1,&range),"flush RT input");}}
    void build(Acceleration& a,VkAccelerationStructureGeometryKHR& geometry,uint32_t count,bool tlas) {
        VkAccelerationStructureBuildGeometryInfoKHR info{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR};
        info.type=tlas?VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR:VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
        info.flags=VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;info.mode=VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;info.geometryCount=1;info.pGeometries=&geometry;
        VkAccelerationStructureBuildSizesInfoKHR sizes{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR};getSizes(device,VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,&info,&count,&sizes);
        createBuffer(a.storage,sizes.accelerationStructureSize,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VkAccelerationStructureCreateInfoKHR create{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR};create.type=info.type;create.buffer=a.storage.handle;create.size=sizes.accelerationStructureSize;
        check(createAS(device,&create,nullptr,&a.handle),"create patch acceleration structure");
        Buffer scratch;createBuffer(scratch,sizes.buildScratchSize+as.minAccelerationStructureScratchOffsetAlignment,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        info.dstAccelerationStructure=a.handle;info.scratchData.deviceAddress=aligned(address(scratch),as.minAccelerationStructureScratchOffsetAlignment);
        begin();
        VkMemoryBarrier before{VK_STRUCTURE_TYPE_MEMORY_BARRIER};before.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;before.dstAccessMask=VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,0,1,&before,0,nullptr,0,nullptr);
        VkAccelerationStructureBuildRangeInfoKHR range{};range.primitiveCount=count;const auto* ranges=&range;buildAS(command,1,&info,&ranges);
        VkMemoryBarrier after{VK_STRUCTURE_TYPE_MEMORY_BARRIER};after.srcAccessMask=VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;after.dstAccessMask=VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR|VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,1,&after,0,nullptr,0,nullptr);submit();release(scratch);
    }
    void init(uint32_t count,const std::string& domainFile,const std::filesystem::path& directory) {
        Runner::init(count,domainFile,{"",""},true);
#define RT_PROC(member,name) member=(PFN_##name)vkGetDeviceProcAddr(device,#name);if(!member)throw std::runtime_error("Missing " #name)
        RT_PROC(createAS,vkCreateAccelerationStructureKHR);RT_PROC(destroyAS,vkDestroyAccelerationStructureKHR);
        RT_PROC(getSizes,vkGetAccelerationStructureBuildSizesKHR);RT_PROC(getASAddress,vkGetAccelerationStructureDeviceAddressKHR);
        RT_PROC(buildAS,vkCmdBuildAccelerationStructuresKHR);RT_PROC(createPipelines,vkCreateRayTracingPipelinesKHR);
        RT_PROC(getHandles,vkGetRayTracingShaderGroupHandlesKHR);RT_PROC(traceRays,vkCmdTraceRaysKHR);
#undef RT_PROC
        VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};props.pNext=&rt;rt.pNext=&as;vkGetPhysicalDeviceProperties2(physical,&props);
        if(count>rt.maxRayDispatchInvocationCount||rt.maxRayRecursionDepth<1||rt.maxRayHitAttributeSize<8)throw std::runtime_error("Unsupported ray dispatch/attribute limits");
        std::array<VkAabbPositionsKHR,8> boxes{};std::ifstream bounds(directory/"bounds.txt");
        for(auto& box:boxes)for(auto ptr:{&box.minX,&box.minY,&box.minZ,&box.maxX,&box.maxY,&box.maxZ})if(!(bounds>>*ptr))throw std::runtime_error("Incomplete production water AABBs");
        std::array<VkAccelerationStructureInstanceKHR,8> instances{};
        for(uint32_t i=0;i<8;i++) {
            auto& box=boxes[i];
            upload(bottoms[i].input,&box,sizeof(box),VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
            VkAccelerationStructureGeometryKHR geometry{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};geometry.geometryType=VK_GEOMETRY_TYPE_AABBS_KHR;geometry.flags=VK_GEOMETRY_OPAQUE_BIT_KHR;
            auto& aabb=geometry.geometry.aabbs;aabb.sType=VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_AABBS_DATA_KHR;aabb.data.deviceAddress=address(bottoms[i].input);aabb.stride=sizeof(box);
            build(bottoms[i],geometry,1,false);
            auto& instance=instances[i];instance.transform.matrix[0][0]=instance.transform.matrix[1][1]=instance.transform.matrix[2][2]=1;
            instance.instanceCustomIndex=i;instance.mask=1u<<i;instance.accelerationStructureReference=accelerationAddress(bottoms[i]);
        }
        upload(top.input,instances.data(),sizeof(instances),VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
        VkAccelerationStructureGeometryKHR geometry{VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR};geometry.geometryType=VK_GEOMETRY_TYPE_INSTANCES_KHR;
        geometry.geometry.instances.sType=VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR;geometry.geometry.instances.data.deviceAddress=address(top.input);
        build(top,geometry,uint32_t(instances.size()),true);
        VkWriteDescriptorSetAccelerationStructureKHR acceleration{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR};acceleration.accelerationStructureCount=1;acceleration.pAccelerationStructures=&top.handle;
        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};write.pNext=&acceleration;write.dstSet=descriptor;write.dstBinding=102;write.descriptorCount=1;write.descriptorType=VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;vkUpdateDescriptorSets(device,1,&write,0,nullptr);
        for(uint32_t variant=0;variant<2;variant++) {
            std::array<std::string,4> files={"raygen.spv","miss.spv","closest.spv",variant?"candidate.spv":"production.spv"};
            std::array<VkShaderModule,4> modules{};std::array<VkPipelineShaderStageCreateInfo,4> stages{};
            std::array<VkShaderStageFlagBits,4> types={VK_SHADER_STAGE_RAYGEN_BIT_KHR,VK_SHADER_STAGE_MISS_BIT_KHR,VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,VK_SHADER_STAGE_INTERSECTION_BIT_KHR};
            for(uint32_t j=0;j<4;j++) {
                std::ifstream file(directory/files[j],std::ios::binary|std::ios::ate);auto bytes=file.tellg();if(!file||bytes<=0||bytes%4)throw std::runtime_error("Invalid RT shader file");
                std::vector<uint32_t> words(size_t(bytes)/4);file.seekg(0);file.read((char*)words.data(),bytes);
                VkShaderModuleCreateInfo module{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};module.codeSize=size_t(bytes);module.pCode=words.data();check(vkCreateShaderModule(device,&module,nullptr,&modules[j]),"create patch shader module");
                stages[j]={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};stages[j].stage=types[j];stages[j].module=modules[j];stages[j].pName="main";
            }
            std::array<VkRayTracingShaderGroupCreateInfoKHR,3> groups{};
            for(auto& group:groups){group.sType=VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR;group.generalShader=group.closestHitShader=group.anyHitShader=group.intersectionShader=VK_SHADER_UNUSED_KHR;}
            groups[0].type=groups[1].type=VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR;groups[0].generalShader=0;groups[1].generalShader=1;
            groups[2].type=VK_RAY_TRACING_SHADER_GROUP_TYPE_PROCEDURAL_HIT_GROUP_KHR;groups[2].closestHitShader=2;groups[2].intersectionShader=3;
            VkRayTracingPipelineCreateInfoKHR create{VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR};create.stageCount=uint32_t(stages.size());create.pStages=stages.data();create.groupCount=uint32_t(groups.size());create.pGroups=groups.data();create.maxPipelineRayRecursionDepth=1;create.layout=layout;
            auto status=createPipelines(device,VK_NULL_HANDLE,VK_NULL_HANDLE,1,&create,nullptr,&pipelines[variant]);for(auto module:modules)vkDestroyShaderModule(device,module,nullptr);check(status,"create procedural patch pipeline");
            auto stride=aligned(rt.shaderGroupHandleSize,std::max(rt.shaderGroupBaseAlignment,rt.shaderGroupHandleAlignment));
            if(stride>rt.maxShaderGroupStride)throw std::runtime_error("SBT stride exceeds device limit");
            std::vector<uint8_t> handles(3*rt.shaderGroupHandleSize);check(getHandles(device,pipelines[variant],0,3,handles.size(),handles.data()),"get patch shader handles");
            auto& sbt=sbts[variant];createBuffer(sbt,3*stride+rt.shaderGroupBaseAlignment,VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
            check(vkMapMemory(device,sbt.memory,0,VK_WHOLE_SIZE,0,&sbt.mapped),"map patch SBT");auto base=address(sbt),start=aligned(base,rt.shaderGroupBaseAlignment);
            for(uint32_t j=0;j<3;j++){std::memcpy((char*)sbt.mapped+(start-base)+j*stride,handles.data()+j*rt.shaderGroupHandleSize,rt.shaderGroupHandleSize);regions[variant][j]={start+j*stride,stride,stride};}visible(sbt);
        }
    }
    double dispatch(uint32_t variant,uint32_t count,bool shuffled) {
        begin();barrier(output,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_SHADER_WRITE_BIT,shaderStages,shaderStages);
        vkCmdResetQueryPool(command,queries,0,2);vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,pipelines[variant]);
        vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,layout,0,1,&descriptor,0,nullptr);Push push{count,uint32_t(shuffled),uint32_t(scene)};
        vkCmdPushConstants(command,layout,VK_SHADER_STAGE_RAYGEN_BIT_KHR|VK_SHADER_STAGE_INTERSECTION_BIT_KHR,0,sizeof(push),&push);
        VkStridedDeviceAddressRegionKHR callable{};auto& r=regions[variant];
        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0);traceRays(command,&r[0],&r[1],&r[2],&callable,count,1,1);vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1);submit();
        uint64_t times[2]{};check(vkGetQueryPoolResults(device,queries,0,2,sizeof(times),times,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT),"read RT timestamps");
        uint64_t mask=timestampBits==64?UINT64_MAX:((uint64_t(1)<<timestampBits)-1);return double((times[1]-times[0])&mask)*properties.limits.timestampPeriod/1e6;
    }
    void close(){if(device){vkDeviceWaitIdle(device);if(top.handle)destroyAS(device,top.handle,nullptr);top.handle={};release(top.storage);release(top.input);
        for(auto& a:bottoms){if(a.handle)destroyAS(device,a.handle,nullptr);a.handle={};release(a.storage);release(a.input);}for(auto& sbt:sbts)release(sbt);}Runner::close();}
};

int main(int argc,char** argv) try {
    if(argc!=4 && argc!=5)throw std::runtime_error("Usage: water_trace domain.txt output-directory ray-count [scene]");
    bool scene=argc==5;if(scene && std::string(argv[4])!="scene")throw std::runtime_error("Unknown scene mode");
    uint32_t count=std::stoul(argv[3]);if(count<4096||count%4096||count>4'194'240)throw std::runtime_error("Ray count must be a bounded multiple of 4096");
    std::filesystem::path directory(argv[2]);if(std::filesystem::exists(directory/"results.json"))throw std::runtime_error("Use a fresh output directory");
    TraceRunner gpu;gpu.scene=scene;gpu.init(count,argv[1],directory);std::vector<Run> runs;
    for(bool shuffled:{false,true}) {
        std::array<Run,2> pair={Run{"production",shuffled},Run{"candidate",shuffled}};
        for(uint32_t v=0;v<2;v++){gpu.dispatch(v,count,shuffled);pair[v].correctness=gpu.readback(count,directory/(pair[v].variant+(shuffled?"-shuffled.csv":"-coherent.csv")));}
        for(uint32_t warm=0;warm<6;warm++)gpu.dispatch(warm&1,count,shuffled);
        for(int sample=0;sample<20;sample++)for(int order=0;order<2;order++){uint32_t v=(sample&1)?1-order:order;pair[v].milliseconds.push_back(gpu.dispatch(v,count,shuffled));}
        for(auto& run:pair)runs.push_back(std::move(run));
    }
    auto name=std::string(gpu.properties.deviceName),driver=std::string(gpu.driver.driverInfo);gpu.close();bool correct=gpu.errors==0;
    std::ofstream json(directory/"results.json");json<<std::setprecision(9)<<"{\n  \"probe\": "<<std::quoted(scene?"finite-patch-ray-tracing-scene":"finite-patch-ray-tracing")<<",\n  \"gpu\": "<<std::quoted(name)<<",\n  \"driver\": "<<std::quoted(driver)<<",\n  \"raysPerDispatch\": "<<count<<",\n  \"validationErrors\": "<<gpu.errors.load()<<",\n  \"runs\": [\n";
    for(size_t i=0;i<runs.size();i++) {
        auto& r=runs[i];auto sorted=r.milliseconds;std::sort(sorted.begin(),sorted.end());double median=(sorted[9]+sorted[10])*.5;auto& c=r.correctness;
        if(r.variant=="candidate"&&c.failures)correct=false;
        std::cout<<"WATER_TRACE "<<r.variant<<" shuffled="<<r.shuffled<<" medianMs="<<median<<" maxMs="<<sorted.back()<<" residualFailures="<<c.failures<<" maxResidual="<<c.maxResidual<<'\n';
        json<<"    {\"variant\": "<<std::quoted(r.variant)<<", \"shuffled\": "<<(r.shuffled?"true":"false")<<", \"residualFailures\": "<<c.failures<<", \"maxResidualBlocks\": "<<c.maxResidual<<", \"maxReportedHitEvaluations\": "<<c.maxWork<<", \"medianMs\": "<<median<<", \"samplesMs\": [";
        for(size_t j=0;j<r.milliseconds.size();j++)json<<(j?", ":"")<<r.milliseconds[j];json<<"]}"<<(i+1<runs.size()?",":"")<<'\n';
    }
    json<<"  ],\n  \"limits\": \"Synthetic procedural AABB traversal, eight triangles, selected-mask or whole-scene mode as named by probe. Not a whole world/frame benchmark. Miss work/exhaustion is not instrumented; CPU-reference hit/miss checks remain required.\"\n}\n";
    return correct?0:1;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 2;}
