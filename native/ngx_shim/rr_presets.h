#pragma once
#include "nvsdk_ngx_defs_dlssd.h"

// NGX's capability parameters survive feature destruction. Always overwrite the hints, including
// Default (0), so selecting Default after F cannot retain the previous explicit model.
template<class Setter> void ngxshimApplyRrPreset(unsigned int preset,Setter&& set) {
    set(NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_DLAA,preset);
    set(NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Quality,preset);
    set(NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Balanced,preset);
    set(NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance,preset);
    set(NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraPerformance,preset);
    set(NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraQuality,preset);
}
