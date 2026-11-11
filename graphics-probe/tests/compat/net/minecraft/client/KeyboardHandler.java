package net.minecraft.client;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.world.entity.FakePlayer;

public class KeyboardHandler {
    /** Every synthetic key event the bridge sends: key, action, mods, window. */
    public List<long[]> pressed = new ArrayList<>();

    public void keyPress(long window, int key, int scan, int action, int mods) {
        pressed.add(new long[] {key, action, mods, window});
    }
}
