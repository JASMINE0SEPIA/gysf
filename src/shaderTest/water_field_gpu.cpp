#define GYSF_WATER_GPU_LIBRARY
#include "water_gpu.cpp"
static std::vector<uint32_t> fieldImage(const std::filesystem::path& path) {
    std::ifstream file(path,std::ios::binary|std::ios::ate);
    if(!file||file.tellg()<16||file.tellg()%4)throw std::runtime_error("Missing production Java field fixture");
    std::vector<uint32_t> data(size_t(file.tellg())/4);file.seekg(0);file.read((char*)data.data(),data.size()*4);return data;
}
struct FieldRunner:Runner {
    std::array<Buffer,2> fields{};
    ~FieldRunner(){releaseFields();}
    void releaseFields() {
        if(device)vkDeviceWaitIdle(device);
        for(auto& b:fields) {
            if(b.mapped)vkUnmapMemory(device,b.memory);if(b.handle)vkDestroyBuffer(device,b.handle,nullptr);
            if(b.memory)vkFreeMemory(device,b.memory,nullptr);b={};
        }
    }
    void load(const std::filesystem::path& directory) {
        std::array<uint64_t,2> addresses{};
        for(int i=0;i<2;i++) {
            auto data=fieldImage(directory/(i?"water-field-previous.bin":"water-field-current.bin"));
            auto& b=fields[i];createBuffer(b,data.size()*4,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
            check(vkMapMemory(device,b.memory,0,VK_WHOLE_SIZE,0,&b.mapped),"map field");std::memcpy(b.mapped,data.data(),data.size()*4);
            if(!b.coherent){VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};range.memory=b.memory;range.size=VK_WHOLE_SIZE;check(vkFlushMappedMemoryRanges(device,1,&range),"flush field");}
            VkBufferDeviceAddressInfo info{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO};info.buffer=b.handle;addresses[i]=vkGetBufferDeviceAddress(device,&info);
            if(!addresses[i])throw std::runtime_error("Zero field BDA");
        }
        std::memcpy(staging.mapped,addresses.data(),sizeof(addresses));flush(false);begin();
        barrier(staging,VK_ACCESS_HOST_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        barrier(domains,VK_ACCESS_SHADER_READ_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferCopy copy{0,0,sizeof(addresses)};vkCmdCopyBuffer(command,staging.handle,domains.handle,1,&copy);
        barrier(domains,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        for(auto& b:fields)barrier(b,VK_ACCESS_HOST_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        submit();
    }
    uint32_t inspect(const std::filesystem::path& file) {
        begin();barrier(output,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        barrier(staging,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_HOST_READ_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferCopy copy{0,0,output.bytes};vkCmdCopyBuffer(command,output.handle,staging.handle,1,&copy);
        barrier(staging,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT);submit();flush(true);
        auto data=static_cast<Result*>(staging.mapped);uint32_t failures=0;float maxError=0,maxWork=0;
        std::ofstream csv(file);csv<<"case,failures,error,work,root_case\n"<<std::setprecision(9);
        for(int i=0;i<4096;i++) {
            auto r=data[i];if(r.t!=0&&failures<16)std::cerr<<"FIELD_GPU_CASE "<<i<<" failures="<<r.t<<" error="<<r.residual<<" work="<<r.work<<'\n';
            if(!std::isfinite(r.t)||!std::isfinite(r.residual)||!std::isfinite(r.work))failures++;
            else failures+=uint32_t(r.t);
            maxError=std::max(maxError,r.residual);maxWork=std::max(maxWork,r.work);
            csv<<i<<','<<r.t<<','<<r.residual<<','<<r.work<<','<<r.exhausted<<'\n';
        }
        std::cout<<"WATER_FIELD_GPU cases=4096 sampling=3840 roots=256 failures="<<failures
                 <<" maxError="<<maxError<<" maxWork="<<maxWork<<" validationErrors="<<errors<<'\n';
        return failures;
    }
};
int main(int argc,char** argv) try {
    if(argc!=5)throw std::runtime_error("Usage: water_field_gpu probe.spv domain.txt fixture-directory output.csv");
    FieldRunner gpu;gpu.init(4096,argv[2],{argv[1],argv[1]},false,true);gpu.load(argv[3]);
    gpu.dispatch(0,4096,false);auto failures=gpu.inspect(argv[4]);gpu.releaseFields();gpu.close();return failures||gpu.errors?1:0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 2;}
