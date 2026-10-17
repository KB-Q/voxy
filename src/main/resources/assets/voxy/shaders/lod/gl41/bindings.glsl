//GL 4.1 bindings: no SSBOs, buffers are read through texture buffers
layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec3 baseSectionPos;
    uint frameId;
    vec3 cameraSubPos;
};

#ifdef BLOCK_MODEL_TEXTURE_BINDING
layout(binding = BLOCK_MODEL_TEXTURE_BINDING) uniform sampler2D blockModelAtlas;
#endif

#ifndef Quad
#define Quad ivec2
#endif
#ifdef QUAD_BUFFER_BINDING
layout(binding = QUAD_BUFFER_BINDING) uniform usamplerBuffer quadTex;//RG32UI, x is the low word of the 64 bit quad
Quad getQuad(uint id) {
    return Quad(texelFetch(quadTex, int(id)).xy);
}
#endif

#ifdef MODEL_BUFFER_BINDING
layout(binding = MODEL_BUFFER_BINDING) uniform usamplerBuffer modelTex;//RGBA32UI, 4 texels per 64 byte model
BlockModel getModel(uint id) {
    int base = int(id)*4;
    uvec4 a = texelFetch(modelTex, base);
    uvec4 b = texelFetch(modelTex, base+1);
    BlockModel model;
    model.faceData[0] = a.x;
    model.faceData[1] = a.y;
    model.faceData[2] = a.z;
    model.faceData[3] = a.w;
    model.faceData[4] = b.x;
    model.faceData[5] = b.y;
    model.flagsA = b.z;
    model.colourTint = b.w;
    model.customId = texelFetch(modelTex, base+2).x;
    for (int i = 0; i < 7; i++) {
        model._pad[i] = 0u;
    }
    return model;
}
#endif

#ifdef MODEL_COLOUR_BUFFER_BINDING
layout(binding = MODEL_COLOUR_BUFFER_BINDING) uniform usamplerBuffer modelColourTex;//R32UI
uint getModelColour(uint id) {
    return texelFetch(modelColourTex, int(id)).x;
}
#endif

#ifdef DRAW_ENTRY_BINDING
layout(binding = DRAW_ENTRY_BINDING) uniform usamplerBuffer drawEntries;//RGBA32UI: quad start, quad count, section position
#endif
