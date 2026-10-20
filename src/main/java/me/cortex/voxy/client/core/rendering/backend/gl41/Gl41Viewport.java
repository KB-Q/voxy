package me.cortex.voxy.client.core.rendering.backend.gl41;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;

//The GL 4.1 renderer keeps all per frame state (draw entries) in the section renderer, there is no gpu side render list
public class Gl41Viewport extends Viewport<Gl41Viewport> {
    public Gl41Viewport(RenderProperties properties) {
        super(properties);
    }

    @Override
    public GlBuffer getRenderList() {
        return null;
    }
}
