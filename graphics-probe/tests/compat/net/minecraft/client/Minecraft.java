package net.minecraft.client;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.world.entity.FakePlayer;

public class Minecraft {
    public static Minecraft instance;
    public FakePlayer player = new FakePlayer();
    public Options options = new Options();
    public Object screen;
    public Window fakeWindow = new Window();
    public KeyboardHandler keyboard = new KeyboardHandler();

    public Window getWindow() { return fakeWindow; }
}
