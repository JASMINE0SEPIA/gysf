#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:terrainglobals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;
layout(location = 0) in vec4 materialTint;
layout(location = 1) in vec2 texCoord;
#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord, 1.0 / TextureSize)
        : sampleNearest(Sampler0, texCoord, 1.0 / TextureSize)) * materialTint;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) discard;
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#elif defined(OIT_ACCUMULATE)
    fragColor = sampleColorForAccumulation(color);
#else
    fragColor = color;
#endif
}
