using RaytracingAccelerationStructure=void*;
#include "water-patch-generated.cpp"
#include <array>
#include <vector>
#include <fstream>
#include <iomanip>
#include <cstring>
#include <iostream>
#include <string>
float f(uint32_t u){float value;std::memcpy(&value,&u,4);return value;}
int main(int argc,char** argv){
 std::array<uint32_t,23> out{};std::vector<Vector<float,4>> rays(4096);
 std::string rayTable,geometry;
 for(int i=1;i<argc;i++) {
  std::string arg=argv[i];
  if(arg=="--ray-table" && i+1<argc)rayTable=argv[++i];
  else if(arg=="--geometry" && i+1<argc)geometry=argv[++i];
  else if(argc==2)rayTable=arg;
  else return 2;
 }
 std::vector<Vector<float,4>> packed;
 if(!geometry.empty()) {
  packed.resize(40);std::ifstream input(geometry);
  for(auto& v:packed)for(auto ptr:{&v.x,&v.y,&v.z,&v.w})if(!(input>>*ptr))return 2;
 }
 GlobalParams_0 params{};params.result_0={out.data(),out.size()};params.rayResults_0={rays.data(),rays.size()};
 params.packedPrimitives_0={packed.data(),packed.size()};
 ComputeThreadVaryingInput input{};patchProbe_Thread(&input,nullptr,&params);
 std::cout<<"WATER_PATCH_PROBE cases="<<out[1]<<" failures="<<out[0]<<" hits="<<out[2]<<" misses="<<out[3]
          <<" referenceDisagreements="<<out[4]<<" exhausted="<<out[5]<<" maxWork="<<out[6]<<" totalWork="<<out[7]
          <<" maxResidual="<<f(out[8])<<" maxDistanceError="<<f(out[9])<<" continuityFailures="<<out[10]
          <<" analyticClipCases="<<out[12]<<" analyticClipFailures="<<out[11]
          <<" gradientFailures="<<out[13]<<" maxGradientError="<<f(out[14])<<" boundsFailures="<<out[15]
          <<" curvatureFailures="<<out[16]<<" maxCurvatureFraction="<<f(out[17])
          <<" flatPrimitiveCases="<<out[19]<<" flatPrimitiveFailures="<<out[18]
          <<" runtimeCases="<<out[21]<<" runtimeFailures="<<out[20]<<" maxGuideNormalError="<<f(out[22])<<'\n';
 if(!rayTable.empty()){std::ofstream file(rayTable);if(!file)return 2;file<<"index,hit_t,reference_t,distance_error,reference_disagreement\n"<<std::setprecision(9);
  for(size_t i=0;i<rays.size();i++)file<<i<<','<<rays[i].x<<','<<rays[i].y<<','<<rays[i].z<<','<<rays[i].w<<'\n';}
 return out[1]==4096 && out[0]==0 && out[2]>0 && out[3]>0 && out[4]==0 && out[5]==0 && out[10]==0
     && out[11]==0 && out[12]==21 && out[13]==0 && out[15]==0 && out[16]==0 && out[18]==0 && out[19]==10
     && out[20]==0 && out[21]==4273?0:1;
}
