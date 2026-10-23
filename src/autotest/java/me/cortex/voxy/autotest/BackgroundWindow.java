package me.cortex.voxy.autotest;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.macosx.ObjCRuntime;

//Keeps test runs from interrupting other work on macOS: the window is created without focus (no app activation) and
// ordered behind every other window. -Dvoxy.autotest.foreground=true restores the normal focused window.
// A fully covered window is still rendered at full rate with vsync off, which the test runs use
public final class BackgroundWindow {
    private static final boolean ENABLED = Platform.get() == Platform.MACOSX && !Boolean.getBoolean("voxy.autotest.foreground");

    private BackgroundWindow() {}

    public static void applyHints() {
        if (!ENABLED) return;
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
    }

    public static void sendToBack(long glfwWindow) {
        if (!ENABLED) return;
        long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);
        long msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        if (nsWindow == 0 || msgSend == 0) return;
        //[nsWindow orderBack:nil], keeps the window on screen behind the other windows without making it key
        JNI.invokePPPP(nsWindow, ObjCRuntime.sel_getUid("orderBack:"), 0L, msgSend);
    }
}
