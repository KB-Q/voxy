package me.cortex.voxy.autotest.mixin;

import com.mojang.blaze3d.platform.Window;
import me.cortex.voxy.autotest.BackgroundWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Test runs open the game window without taking focus and put it behind the other windows, see BackgroundWindow
@Mixin(Window.class)
public class MixinWindow {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
    private void voxy_autotest$noFocusHints(CallbackInfo ci) {
        BackgroundWindow.applyHints();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy_autotest$sendToBack(CallbackInfo ci) {
        BackgroundWindow.sendToBack(((Window) (Object) this).getWindow());
    }
}
