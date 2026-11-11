import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.entity.FakePlayer;
import net.minecraft.world.entity.player.Inventory;

import orbis.compat.ControllerBridge;

/**
 * Drives ControllerBridge against the fake Minecraft with Mojang's member names and a scripted pad,
 * then checks what reached the game: holds, taps, look, hotbar and the menu keys. Exit status 1 on
 * any failure.
 */
public class ControllerBridgeMojangTest {
    private static int failures;

    // GLFW codes the bridge sends (stable GLFW 3 values).
    private static final int KEY_ESCAPE = 256, KEY_ENTER = 257, KEY_UP = 265, KEY_DOWN = 264;

    public static void main(String[] args) {
        Minecraft mc = new Minecraft();
        Minecraft.instance = mc;
        Options options = mc.options;
        Inventory inventory = mc.player.inventory;

        // Walk forward and jump; press Square once; hold L2; look right; R1 cycles the hotbar.
        set(1, 1, -1f);            // left stick up (axis 1, the left stick's Y)
        set(0, 0, 1f);             // Cross down (button 0)
        set(0, 2, 1f);             // Square down (button 2, a tap)
        set(0, 10, 1f);            // R1 down (button 10, a tap)
        set(1, 2, 0.6f);           // right stick right (axis 2)
        set(1, 4, 1f);             // L2 down (axis 4, a hold)
        ControllerBridge.tick(mc);
        check(options.keyUp.isDown, "left stick up holds forward");
        check(options.keyJump.isDown, "Cross holds jump");
        check(options.keyInventory.clickCount == 1, "Square taps inventory once");
        check(options.keyAttack.isDown, "L2 holds attack");
        check(inventory.selected == 1, "R1 moves the hotbar to slot 2");
        check(mc.player.turned.size() == 1 && Math.abs(mc.player.turned.get(0)[0] - 0.6) < 1e-6,
                "the right stick turns the player");

        // Release everything: holds drop, the tap does not repeat.
        release();
        ControllerBridge.tick(mc);
        check(!options.keyUp.isDown, "releasing the stick releases forward");
        check(!options.keyJump.isDown, "releasing Cross releases jump");
        check(!options.keyAttack.isDown, "releasing L2 releases attack");
        check(options.keyInventory.clickCount == 1, "a released button does not tap again");

        // A menu open: Circle sends Escape through the game's keyboard handler, Cross sends Enter.
        mc.screen = new Object();
        set(0, 1, 1f);             // Circle down (button 1)
        set(0, 0, 1f);             // Cross down
        ControllerBridge.tick(mc);
        check(event(mc.keyboard, KEY_ESCAPE, 1, 0), "Circle presses Escape in a menu");
        check(event(mc.keyboard, KEY_ENTER, 1, 0), "Cross presses Enter in a menu");
        check(!options.keyJump.isDown, "game holds are released in a menu");
        release();
        ControllerBridge.tick(mc);
        check(event(mc.keyboard, KEY_ESCAPE, 0, 0), "Circle released releases Escape");

        // Without a controller everything stays released and nothing breaks.
        org.lwjgl.glfw.GLFW.present = false;
        mc.screen = null;
        ControllerBridge.tick(mc);
        check(!options.keyUp.isDown && mc.player.turned.size() == 1, "no controller changes nothing");

        if (failures > 0) {
            System.out.println(failures + " controller checks failed");
            System.exit(1);
        }
        System.out.println("ControllerBridgeMojangTest: all checks passed");
    }

    private static void set(int kind, int index, float value) {
        if (kind == 0) org.lwjgl.glfw.GLFW.BUTTONS[index] = value;
        else org.lwjgl.glfw.GLFW.AXES[index] = value;
    }

    private static void release() {
        java.util.Arrays.fill(org.lwjgl.glfw.GLFW.BUTTONS, 0f);
        java.util.Arrays.fill(org.lwjgl.glfw.GLFW.AXES, 0f);
    }

    private static boolean event(KeyboardHandler keyboard, int key, int action, int mods) {
        for (long[] e : keyboard.pressed)
            if (e[0] == key && e[1] == action && e[2] == mods && e[3] == 77L) return true;
        return false;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }
}
