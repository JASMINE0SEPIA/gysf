#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(GYSF_BLOCK) && !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
layout(location = 3) in ivec2 UV1;
uniform sampler2D Sampler1;
layout(location = 2) out vec4 overlayColor;
#endif
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif

layout(location = 0) out vec4 materialTint;
layout(location = 1) out vec2 texCoord;
#ifdef GLINT
layout(location = 3) out vec2 glintCoord;
#endif

void main() {
    vec3 pos = Position;
#ifdef GYSF_BLOCK
    pos += ModelOffset;
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    materialTint = Color;
    texCoord = UV0;
#ifdef APPLY_TEXTURE_MATRIX
    texCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
#if !defined(GYSF_BLOCK) && !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif
#ifdef GLINT
    #ifdef GLINT_SPECIAL
    glintCoord = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    glintCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    #endif
#endif
}
