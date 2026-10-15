#include "rr_presets.h"
#include <map>
#include <string>
#include <cstdio>

int main() {
    // A persistent parameter block, as used by the actual NGX feature path.
    std::map<std::string,unsigned int> params;
    auto set=[&](const char* key,unsigned int value){params[key]=value;};
    for(unsigned int preset : {6u,0u,4u,6u,0u}) {
        ngxshimApplyRrPreset(preset,set);
        if(params.size()!=6)return 1;
        for(auto& entry:params)if(entry.second!=preset)return 2;
        // Check independent SDK keys, including the player's performance mode and native DLAA.
        if(params.at(NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance)!=preset)return 3;
        if(params.at(NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_DLAA)!=preset)return 4;
    }
    std::puts("RR preset persistence: F -> Default -> D -> F -> Default passed for all six modes");
}
