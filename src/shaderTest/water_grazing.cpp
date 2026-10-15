using RaytracingAccelerationStructure=void*;
#include "water-grazing-generated.cpp"
#include <array>
#include <cstring>
#include <iostream>
#include <string>
float f(uint32_t u){float value;std::memcpy(&value,&u,4);return value;}
int main(int argc,char** argv){
 if(argc>2) return 2;
 bool prototype=argc==2 && std::string(argv[1])=="--prototype";
 std::array<uint32_t,13> out{};GlobalParams_0 params{};params.result_0={out.data(),out.size()};
 ComputeThreadVaryingInput input{};grazingProbe_Thread(&input,nullptr,&params);
 std::cout<<"WATER_GRAZING_DIAGNOSTIC cases="<<out[1]<<" residualFailures="<<out[0]<<" maxResidualBlocks="<<f(out[2])
          <<" worstAngle="<<f(out[3])<<" worstStrength="<<f(out[4])<<" worstTime="<<f(out[5])<<" maxWork="<<out[6]<<" totalWork="<<out[7]<<" exhausted="<<out[8]
          <<" references="<<out[9]<<" rootFailures="<<out[10]<<" maxRootDistanceError="<<f(out[11])<<" referenceDisagreements="<<out[12]<<'\n';
 return out[1]==4096 && out[0]==0 && out[8]==0 && out[9]==(prototype?32u:0u) && out[10]==0 && out[12]==0?0:1;
}

