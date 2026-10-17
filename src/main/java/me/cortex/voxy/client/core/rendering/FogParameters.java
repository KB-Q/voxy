package me.cortex.voxy.client.core.rendering;

//1.20.1 replacement for sodium 0.6+'s FogParameters, only the environmental fog and colour are tracked
// environmental fog is fog that is not caused by the render distance (fluids, effects, thick biome fog)
public record FogParameters(float environmentalStart, float environmentalEnd, float red, float green, float blue, float alpha) {
    public static final float NO_FOG = 99999999;
    public static final FogParameters NONE = new FogParameters(NO_FOG, NO_FOG, 0, 0, 0, 0);

    //Captured from FogRenderer.setupFog for the terrain fog mode, before voxy modifies the shader fog
    private static volatile FogParameters CAPTURED = NONE;

    public static void capture(FogParameters parameters) {
        CAPTURED = parameters;
    }

    public static FogParameters captured() {
        return CAPTURED;
    }
}
