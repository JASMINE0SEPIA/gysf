// The host probe never traverses the scene; Slang still emits this unused global binding's type.
using RaytracingAccelerationStructure = void*;
#include "water-origin-generated.cpp"
#include <array>
#include <iostream>

int main() {
    std::array<uint32_t, 3> output{};
    GlobalParams_0 params{};
    params.result_0 = {output.data(), output.size()};
    ComputeThreadVaryingInput input{};
    waterOriginProbe_Thread(&input, nullptr, &params);
    std::cout << "WATER_ORIGIN_PROBE cases=" << output[1] << " failures=" << output[0]
              << " oldTirSelfHitReproduced=" << output[2] << '\n';
    return output[0] == 0 && output[1] == 2080 && output[2] == 1 ? 0 : 1;
}
