using RaytracingAccelerationStructure=void*;
#include "water-grazing-generated.cpp"
#include <array>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>
#include <fstream>
#include <iomanip>
float f(uint32_t u){float value;std::memcpy(&value,&u,4);return value;}
int main(int argc,char** argv){
 bool prototype=false,fullReference=false;std::string rayTable;
 for(int i=1;i<argc;i++) {
  std::string arg=argv[i];
  if(arg=="--prototype")prototype=true;
  else if(arg=="--full-reference")fullReference=true;
  else if(arg=="--ray-table" && i+1<argc)rayTable=argv[++i];
  else return 2;
 }
 std::vector<Vector<float,4>> rays(4096);
 std::array<uint32_t,13> out{};GlobalParams_0 params{};params.result_0={out.data(),out.size()};
 params.rayResults_0={rays.data(),rays.size()};
 ComputeThreadVaryingInput input{};grazingProbe_Thread(&input,nullptr,&params);
 std::cout<<"WATER_GRAZING_DIAGNOSTIC cases="<<out[1]<<" residualFailures="<<out[0]<<" maxResidualBlocks="<<f(out[2])
          <<" worstAngle="<<f(out[3])<<" worstStrength="<<f(out[4])<<" worstTime="<<f(out[5])<<" maxWork="<<out[6]<<" totalWork="<<out[7]<<" exhausted="<<out[8]
          <<" references="<<out[9]<<" rootFailures="<<out[10]<<" maxRootDistanceError="<<f(out[11])<<" referenceDisagreements="<<out[12]<<'\n';
 if(!rayTable.empty()){
  std::ofstream file(rayTable);if(!file)return 2;
  file<<"index,hit_t,reference_t,distance_error,reference_disagreement\n"<<std::setprecision(9);
  for(size_t i=0;i<rays.size();i++)file<<i<<','<<rays[i].x<<','<<rays[i].y<<','<<rays[i].z<<','<<rays[i].w<<'\n';
 }
 return out[1]==4096 && out[0]==0 && out[8]==0 && out[9]==(prototype?(fullReference?4096u:32u):0u) && out[10]==0 && out[12]==0?0:1;
}

