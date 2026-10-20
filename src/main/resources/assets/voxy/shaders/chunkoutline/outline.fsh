#version 460 core
#ifndef GL41
layout(early_fragment_tests) in;//GLSL 4.20, without it the depth only pass is still correct
#endif

void main() {}