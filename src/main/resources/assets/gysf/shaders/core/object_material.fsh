#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#ifdef GLINT
#include <minecraft:globals.glsl>
uniform sampler2D GlintSampler;
#endif
#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

uniform sampler2D Sampler0;
layout(location = 0) in vec4 materialTint;
layout(location = 1) in vec2 texCoord;
#if !defined(GYSF_BLOCK) && !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
layout(location = 2) in vec4 overlayColor;
#endif
#ifdef GLINT
layout(location = 3) in vec2 glintCoord;
#endif
#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord);
#ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
#endif
#if defined(ALPHA_CUTOUT) && !defined(GYSF_BLOCK)
    if (color.a < ALPHA_CUTOUT) discard;
#endif
    vec4 tint = materialTint;
#ifdef DISSOLVE
    if (tint.a < texture(DissolveMaskSampler, texCoord).a) discard;
    tint.a = 1.0;
#endif
    color *= tint * ColorModulator;
#if defined(ALPHA_CUTOUT) && defined(GYSF_BLOCK)
    if (color.a < ALPHA_CUTOUT) discard;
#endif
#ifdef GLINT
    color.a = max(color.a, GlintAlpha);
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    // Functional overlays follow the clean base; neither depends on lightmap or EMISSIVE flags.
    #if !defined(GYSF_BLOCK) && !defined(NO_OVERLAY)
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #endif
    #ifdef GLINT
    vec4 glint = GlintAlpha * texture(GlintSampler, glintCoord);
    color.rgb += glint.rgb * glint.rgb;
    #endif
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    fragColor = color;
#endif
}
