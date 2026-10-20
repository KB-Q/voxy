#version 410 core
//GL 4.1 terrain vertex shader, quads3.vert with the per draw data coming from a draw entry instead of gl_BaseInstance
// each instance is one entry, gl_VertexID/4 is the quad within the entry, quads past the entry count are discarded

#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define QUAD_BUFFER_BINDING 24
#define DRAW_ENTRY_BINDING 25
#define LIGHTING_SAMPLER_BINDING 1

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl41/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) flat out uvec4 interData;
layout(location = 1) out vec2 uv;

layout(location = 10) uniform int entryBase;

vec2 taaShift();

void main() {
    taaOffset = taaShift();

    uvec4 entry = texelFetch(drawEntries, entryBase + gl_InstanceID);
    uint local = uint(gl_VertexID)>>2;
    if (local >= entry.y) {
        //Outside the clip volume, the whole quad collapses to one point and is culled
        gl_Position = vec4(-2.0, -2.0, -2.0, 1.0);
        interData = uvec4(0);
        uv = vec2(0);
        return;
    }

    QuadData quad;
    setupQuad(quad, getQuad(entry.x + local), entry.zw, (gl_VertexID&3) == 1);

    uint cornerId = uint(gl_VertexID)&3u;
    gl_Position = getQuadCornerPos(quad, cornerId);
    uv = getCornerUV(quad, cornerId);

    //Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif
