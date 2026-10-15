using RaytracingAccelerationStructure = void*;
#include "water-domain-generated.cpp"
#include <array>
#include <cmath>
#include <fstream>
#include <iostream>
#include <string>

int main(int argc,char** argv) {
    if(argc<2 || argc>3) return 2;
    bool expectLegacy=argc==3 && std::string(argv[2])=="--expect-legacy-mask";
    std::ifstream file(argv[1]);
    if(!file) return 2;
    uint32_t cases=0,failures=0,basisFailures=0;
    float worstHeight=0,worstGrad=0,worstBasis=0;
    std::array<Vector<float,4>,17> data{};
    std::array<Vector<float,4>,2> output{},previous{};
    bool first=true;
    while(true) {
        int ox,oz; float x,z,t,footprint,strength,speed,legacyTime;
        if(!(file>>ox>>oz>>x>>z>>t>>footprint>>strength>>speed>>legacyTime)) break;
        data[0]={1,strength,speed,7}; data[1]={x,z,t,footprint};
        data[2]={float(ox),float(oz),legacyTime,0};
        for(int i=0;i<7;i++) {
            float kx,kz,mx,mz,mainPhase,meanderPhase,omega;
            if(!(file>>kx>>kz>>mx>>mz>>mainPhase>>meanderPhase>>omega)) return 2;
            data[3+i*2]={kx,kz,mx,mz}; data[4+i*2]={mainPhase,meanderPhase,omega,0};
        }
        GlobalParams_0 params{};
        params.input_0={data.data(),data.size()}; params.result_0={output.data(),output.size()};
        ComputeThreadVaryingInput input{}; waterDomainProbe_Thread(&input,nullptr,&params);
        worstBasis=std::max(worstBasis,output[1].z);
        if(output[1].z>2e-6f) basisFailures++;
        if(!first) {
            float h=std::max(std::abs(output[0].x-previous[0].x),std::abs(output[0].w-previous[0].w));
            float g=std::max(std::hypot(output[0].y-previous[0].y,output[0].z-previous[0].z),
                    std::hypot(output[1].x-previous[1].x,output[1].y-previous[1].y));
            worstHeight=std::max(worstHeight,h); worstGrad=std::max(worstGrad,g);
            if(!std::isfinite(h)||!std::isfinite(g)||h>1e-4f||g>0.001f) failures++;
            cases++;
        }
        previous=output; first=!first;
    }
    std::cout<<"WATER_DOMAIN_PROBE pairs="<<cases<<" failures="<<failures<<" basisFailures="<<basisFailures
             <<" maxHeightDifference="<<worstHeight<<" maxGradientDifference="<<worstGrad<<" maxBasisDifference="<<worstBasis
             <<" oldMaskReproduced="<<(expectLegacy && failures>0)<<'\n';
    return cases==15552 && (expectLegacy?failures>0:failures==0) && basisFailures==0 && first ? 0 : 1;
}
