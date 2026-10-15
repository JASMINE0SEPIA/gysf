using RaytracingAccelerationStructure=void*;
#include "water-patch-generated.cpp"
#include <array>
#include <vector>
#include <fstream>
#include <iomanip>
#include <cstring>
#include <iostream>
float f(uint32_t u){float value;std::memcpy(&value,&u,4);return value;}
int main(int argc,char** argv){
 std::array<uint32_t,18> out{};std::vector<Vector<float,4>> rays(4096);
 GlobalParams_0 params{};params.result_0={out.data(),out.size()};params.rayResults_0={rays.data(),rays.size()};
 ComputeThreadVaryingInput input{};patchProbe_Thread(&input,nullptr,&params);
 std::cout<<"WATER_PATCH_PROBE cases="<<out[1]<<" failures="<<out[0]<<" hits="<<out[2]<<" misses="<<out[3]
          <<" referenceDisagreements="<<out[4]<<" exhausted="<<out[5]<<" maxWork="<<out[6]<<" totalWork="<<out[7]
          <<" maxResidual="<<f(out[8])<<" maxDistanceError="<<f(out[9])<<" continuityFailures="<<out[10]
          <<" analyticClipCases="<<out[12]<<" analyticClipFailures="<<out[11]
          <<" gradientFailures="<<out[13]<<" maxGradientError="<<f(out[14])<<" boundsFailures="<<out[15]
          <<" curvatureFailures="<<out[16]<<" maxCurvatureFraction="<<f(out[17])<<'\n';
 if(argc==2){std::ofstream file(argv[1]);if(!file)return 2;file<<"index,hit_t,reference_t,distance_error,reference_disagreement\n"<<std::setprecision(9);
  for(size_t i=0;i<rays.size();i++)file<<i<<','<<rays[i].x<<','<<rays[i].y<<','<<rays[i].z<<','<<rays[i].w<<'\n';}
 return out[1]==4096 && out[0]==0 && out[2]>0 && out[3]>0 && out[4]==0 && out[5]==0 && out[10]==0
     && out[11]==0 && out[12]==17 && out[13]==0 && out[15]==0 && out[16]==0?0:1;
}
