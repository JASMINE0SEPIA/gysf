#include "weather-transport-generated.cpp"
#include <array>
#include <iostream>
int main() {
    std::array<uint32_t,2> output{};
    GlobalParams_0 params{};
    params.result_0 = {output.data(), output.size()};
    ComputeThreadVaryingInput input{};
    weatherTransportProbe_Thread(&input, nullptr, &params);
    std::cout << "WEATHER_TRANSPORT cases=" << output[1] << " failures=" << output[0] << '\n';
    return output[0] == 0 && output[1] == 323 ? 0 : 1;
}
