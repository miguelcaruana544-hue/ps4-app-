package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** What the bridge reads back: the same shape LWJGL's GLFWGamepadState has. */
public final class GLFWGamepadState {
    private final ByteBuffer buttons = ByteBuffer.allocate(16);
    private final FloatBuffer axes = FloatBuffer.allocate(6);

    public GLFWGamepadState() {}

    public ByteBuffer buttons() { return buttons; }
    public FloatBuffer axes() { return axes; }
}
