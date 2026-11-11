package net.minecraft.client;
import java.util.ArrayList;
import java.util.List;

public class KeyboardListener {
    /** Every synthetic key event the bridge sends: key, action, mods, window. */
    public List<long[]> pressed = new ArrayList<>();

    public void func_197961_a(long window, int key, int scan, int action, int mods) {
        pressed.add(new long[] {key, action, mods, window});
    }
}
