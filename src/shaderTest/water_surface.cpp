using RaytracingAccelerationStructure = void*;
#include "water-surface-generated.cpp"
#include <array>
#include <cstring>
#include <iostream>

int main() {
    std::array<uint32_t,8> output{};
    GlobalParams_0 params{};
    params.result_0={output.data(),output.size()};
    ComputeThreadVaryingInput input{};
    waterSurfaceProbe_Thread(&input,nullptr,&params);
    float normalError=0,heightError=0;
    std::memcpy(&normalError,&output[6],4); std::memcpy(&heightError,&output[7],4);
    std::cout << "WATER_SURFACE_PROBE cases=" << output[1] << " failures=" << output[0]
              << " zeroAmplitude=" << output[2] << " surfaceNormals=" << output[3]
              << " heightIntersections=" << output[4] << " worldAnchor=" << output[5]
              << " maxNormalError=" << normalError << " maxHeightError=" << heightError << '\n';
    return output[0]==0 && output[1]==6338 ? 0 : 1;
}
