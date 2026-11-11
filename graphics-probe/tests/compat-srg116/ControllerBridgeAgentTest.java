import java.util.Arrays;

import net.minecraft.client.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.PlayerInventory;

/**
 * The agent end to end: this program runs with the compatibility JAR as a -javaagent, builds the
 * fake Minecraft (Forge 1.14-1.16 names), scripts the pad and waits; the agent's thread must find
 * the game and drive it. The main then checks what happened and reports the exit status.
 */
public class ControllerBridgeAgentTest {
    public static void main(String[] args) throws Exception {
        Minecraft mc = new Minecraft();
        Minecraft.f_91068_ = mc;
        GameSettings options = mc.field_71474_y;
        PlayerInventory inventory = mc.field_71439_g.field_71071_bz;

        // Cross held, right stick pushed, Square tapped: what the agent's thread should make happen.
        org.lwjgl.glfw.GLFW.BUTTONS[0] = 1f;
        org.lwjgl.glfw.GLFW.BUTTONS[2] = 1f;
        org.lwjgl.glfw.GLFW.AXES[2] = 0.6f;

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (options.field_74314_A.field_74513_e && mc.field_71439_g.turned.size() > 0
                    && options.field_151445_Q.field_151474_i > 0) {
                System.out.println("ok   the agent drove the game: jump held, camera turned, inventory tapped");
                System.out.println("ControllerBridgeAgentTest: all checks passed");
                System.exit(0);
            }
            Thread.sleep(100);
        }
        System.out.println("FAIL the agent never drove the game");
        System.out.println("     jump held: " + options.field_74314_A.field_74513_e
                + ", turns: " + mc.field_71439_g.turned.size()
                + ", inventory taps: " + options.field_151445_Q.field_151474_i);
        System.exit(1);
    }
}
