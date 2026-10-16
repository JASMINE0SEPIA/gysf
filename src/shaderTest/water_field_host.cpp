using RaytracingAccelerationStructure=void*;
#include "water-field-generated.cpp"
#include <array>
#include <vector>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <algorithm>
std::vector<uint32_t> image(const char* path) {
    std::ifstream file(path,std::ios::binary|std::ios::ate);
    if(!file||file.tellg()<16||file.tellg()%4)throw std::runtime_error("Missing production Java field fixture");
    std::vector<uint32_t> data(size_t(file.tellg())/4);file.seekg(0);file.read((char*)data.data(),data.size()*4);return data;
}
int main(int argc,char** argv) try {
    if(argc!=3)return 2;
    auto current=image(argv[1]),previous=image(argv[2]);
    std::array<uint64_t,2> addresses{uint64_t(current.data()),uint64_t(previous.data())};
    std::vector<Vector<float,4>> results(4096);GlobalParams_0 params{};
    params.fieldResults_0={results.data(),results.size()};params.fieldAddresses_0={addresses.data(),addresses.size()};
    ComputeThreadVaryingInput input{};
    uint32_t failures=0;float maxError=0,maxWork=0;
    for(uint32_t i=0;i<4096;i++) {
        input.groupID.x=i/64;input.groupThreadID.x=i%64;fieldProbe_Thread(&input,nullptr,&params);
        auto r=results[i];if(r.x!=0&&failures<16)std::cerr<<"FIELD_CASE "<<i<<" failures="<<r.x<<" error="<<r.y<<" work="<<r.z<<'\n';
        failures+=uint32_t(r.x);maxError=std::max(maxError,r.y);maxWork=std::max(maxWork,r.z);
    }
    std::cout<<"WATER_FIELD_HOST cases=4096 sampling=3840 roots=256 failures="<<failures
             <<" maxError="<<maxError<<" maxWork="<<maxWork<<'\n';return failures?1:0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 2;}
