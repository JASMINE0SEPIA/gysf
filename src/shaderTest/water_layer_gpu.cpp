#define GYSF_WATER_GPU_LIBRARY
#include "water_gpu.cpp"
static std::vector<uint32_t> image(const std::filesystem::path& path) {
    std::ifstream file(path,std::ios::binary|std::ios::ate);
    if(!file||file.tellg()<16||file.tellg()%4)throw std::runtime_error("Missing packed Java input");
    std::vector<uint32_t> data(size_t(file.tellg())/4);file.seekg(0);file.read((char*)data.data(),data.size()*4);return data;
}
struct LayerRunner:Runner {
    std::array<Buffer,4> inputs{};
    ~LayerRunner(){releaseInputs();}
    void releaseInputs() {
        if(device)vkDeviceWaitIdle(device);
        for(auto& b:inputs) {
            if(b.mapped)vkUnmapMemory(device,b.memory);if(b.handle)vkDestroyBuffer(device,b.handle,nullptr);
            if(b.memory)vkFreeMemory(device,b.memory,nullptr);b={};
        }
    }
    void load(const std::filesystem::path& directory) {
        std::array<uint64_t,4> addresses{};
        const char* names[]={"water-field-current.bin","water-field-previous.bin","water-layer-primitives.bin","water-field-full-cell.bin"};
        for(int i=0;i<4;i++) {
            auto data=image(directory/names[i]);auto& b=inputs[i];
            if(i==2&&data.size()*4!=4096*3*80)throw std::runtime_error("Incorrect production primitive count/stride");
            createBuffer(b,data.size()*4,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
            check(vkMapMemory(device,b.memory,0,VK_WHOLE_SIZE,0,&b.mapped),"map layer input");std::memcpy(b.mapped,data.data(),data.size()*4);
            if(!b.coherent){VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};range.memory=b.memory;range.size=VK_WHOLE_SIZE;check(vkFlushMappedMemoryRanges(device,1,&range),"flush layer input");}
            VkBufferDeviceAddressInfo info{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO};info.buffer=b.handle;addresses[i]=vkGetBufferDeviceAddress(device,&info);
            if(!addresses[i])throw std::runtime_error("Zero layer input BDA");
        }
        std::memcpy(staging.mapped,addresses.data(),sizeof(addresses));flush(false);begin();
        barrier(staging,VK_ACCESS_HOST_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        barrier(domains,VK_ACCESS_SHADER_READ_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferCopy copy{0,0,sizeof(addresses)};vkCmdCopyBuffer(command,staging.handle,domains.handle,1,&copy);
        barrier(domains,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        for(auto& b:inputs)barrier(b,VK_ACCESS_HOST_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        submit();
    }
    uint32_t inspect(const std::filesystem::path& path) {
        begin();barrier(output,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        barrier(staging,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_HOST_READ_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferCopy copy{0,0,output.bytes};vkCmdCopyBuffer(command,output.handle,staging.handle,1,&copy);
        barrier(staging,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT);submit();flush(true);
        auto data=static_cast<Result*>(staging.mapped);uint32_t failures=0;float minGap=100,maxGap=0,maxError=0;
        std::ofstream csv(path);csv<<"case,fixtureFailures,gapBlocks,rootErrorBlocks,coarseSize\n"<<std::setprecision(9);
        for(int i=0;i<4096;i++) {
            auto r=data[i];
            if(r.t!=0&&failures<12)std::cerr<<"LAYER_GPU_CASE "<<i<<" failures="<<r.t<<" gap="<<r.residual<<" error="<<r.work<<'\n';
            if(!std::isfinite(r.t)||!std::isfinite(r.residual)||!std::isfinite(r.work))failures++;
            else failures+=uint32_t(r.t);
            minGap=std::min(minGap,r.residual);maxGap=std::max(maxGap,r.residual);maxError=std::max(maxError,r.work);
            csv<<i<<','<<r.t<<','<<r.residual<<','<<r.work<<','<<r.exhausted<<'\n';
        }
        std::cout<<"WATER_LAYER_GPU cases=4096 fixtureFailures="<<failures<<" minGap="<<minGap<<" maxGap="<<maxGap<<" maxRootError="<<maxError<<" validationErrors="<<errors<<'\n';
        return failures;
    }
};
int main(int argc,char** argv) try {
    if(argc!=5)throw std::runtime_error("Usage: layer_gpu probe.spv domain.txt input-directory output.csv");
    LayerRunner gpu;gpu.init(4096,argv[2],{argv[1],argv[1]},false,true);gpu.load(argv[3]);gpu.dispatch(0,4096,false);
    auto failures=gpu.inspect(argv[4]);gpu.releaseInputs();gpu.close();return failures||gpu.errors?1:0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 2;}
