using RaytracingAccelerationStructure = void*;
#include "water-temporal-generated.cpp"
#include <array>
#include <cstring>
#include <iostream>

static float asFloat(uint32_t bits) {
    float value; std::memcpy(&value,&bits,4); return value;
}

int main() {
    std::array<uint32_t,23> output{};
    GlobalParams_0 params{};
    params.result_0={output.data(),output.size()};
    ComputeThreadVaryingInput input{};
    waterTemporalProbe_Thread(&input,nullptr,&params);
    std::cout << "WATER_TEMPORAL_PROBE cases=" << output[1] << " failures=" << output[0]
              << " currentField=" << output[2] << " maxHeightError=" << asFloat(output[3])
              << " maxNormalError=" << asFloat(output[4]) << '\n';
    constexpr float deltas[6]={0.0f,1.0f/240,1.0f/60,1.0f/30,0.1f,0.25f};
    for(int i=0;i<6;i++) std::cout << "  dt=" << deltas[i] << " failures=" << output[5+i*3]
              << " maxHeightError=" << asFloat(output[6+i*3])
              << " maxNormalError=" << asFloat(output[7+i*3]) << '\n';
    return output[0]==0 && output[1]==12960 ? 0 : 1;
}
