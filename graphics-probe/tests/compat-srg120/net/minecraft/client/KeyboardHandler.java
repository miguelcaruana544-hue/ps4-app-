package net.minecraft.client;
import java.util.ArrayList;
import java.util.List;

public class KeyboardHandler {
    /** Every synthetic key event the bridge sends: key, action, mods, window. */
    public List<long[]> pressed = new ArrayList<>();

    public void m_90893_(long window, int key, int scan, int action, int mods) {
        pressed.add(new long[] {key, action, mods, window});
    }
}
