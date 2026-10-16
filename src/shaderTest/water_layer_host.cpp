using RaytracingAccelerationStructure=void*;
#include "water-layer-generated.cpp"
#include <array>
#include <vector>
#include <fstream>
#include <iostream>
#include <iomanip>
#include <algorithm>
#include <cmath>
static std::vector<uint32_t> image(const char* path) {
    std::ifstream file(path,std::ios::binary|std::ios::ate);
    if(!file||file.tellg()<16||file.tellg()%4)throw std::runtime_error("Missing packed Java input");
    std::vector<uint32_t> data(size_t(file.tellg())/4);file.seekg(0);file.read((char*)data.data(),data.size()*4);return data;
}
int main(int argc,char** argv) try {
    if(argc!=5)throw std::runtime_error("Usage: layer_host current.bin previous.bin primitives.bin output.csv");
    auto current=image(argv[1]),previous=image(argv[2]),packed=image(argv[3]);
    if(packed.size()*4!=4096*2*80)throw std::runtime_error("Incorrect production primitive count/stride");
    std::array<uint64_t,3> addresses{uint64_t(current.data()),uint64_t(previous.data()),uint64_t(packed.data())};
    std::vector<Vector<float,4>> results(4096);GlobalParams_0 params{};
    params.layerResults_0={results.data(),results.size()};params.layerAddresses_0={addresses.data(),addresses.size()};
    ComputeThreadVaryingInput input{};uint32_t failures=0;float minGap=100,maxGap=0,maxError=0;
    std::ofstream csv(argv[4]);csv<<"case,fixtureFailures,gapBlocks,rootErrorBlocks,coarseSize\n"<<std::setprecision(9);
    for(uint32_t i=0;i<4096;i++) {
        input.groupID.x=i/64;input.groupThreadID.x=i%64;fieldProbe_Thread(&input,nullptr,&params);
        auto r=results[i];
        if(r.x!=0&&failures<12)std::cerr<<"LAYER_CASE "<<i<<" failures="<<r.x<<" gap="<<r.y<<" error="<<r.z<<'\n';
        if(!std::isfinite(r.x)||!std::isfinite(r.y)||!std::isfinite(r.z))failures++;
        else failures+=uint32_t(r.x);
        minGap=std::min(minGap,r.y);maxGap=std::max(maxGap,r.y);maxError=std::max(maxError,r.z);
        csv<<i<<','<<r.x<<','<<r.y<<','<<r.z<<','<<r.w<<'\n';
    }
    std::cout<<"WATER_LAYER_HOST cases=4096 fixtureFailures="<<failures<<" minGap="<<minGap<<" maxGap="<<maxGap<<" maxRootError="<<maxError<<'\n';
    return failures?1:0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 2;}
