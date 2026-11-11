package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * A stand-in for the Orbis GLFW facade's gamepad API: scripted buttons and axes the tests set
 * between ticks, read through the same reflective calls ControllerBridge uses at run time.
 * The class and method names match what ControllerBridge resolves.
 */
public final class GLFW {
    /** The scripted pad: 15 buttons (0/1) and 6 axes, as the GLFW gamepad state layout has them. */
    public static final float[] BUTTONS = new float[15];
    public static final float[] AXES = new float[6];
    /** Set false to answer "no controller". */
    public static boolean present = true;

    private GLFW() {}

    public static boolean glfwGetGamepadState(int joystick, GLFWGamepadState state) {
        if (!present) return false;
        for (int i = 0; i < state.buttons().capacity() && i < BUTTONS.length; i++)
            state.buttons().put(i, (byte) (BUTTONS[i] > 0.5f ? 1 : 0));
        for (int i = 0; i < state.axes().capacity() && i < AXES.length; i++)
            state.axes().put(i, AXES[i]);
        return true;
    }

    public static boolean glfwJoystickIsGamepad(int joystick) { return present; }
}
