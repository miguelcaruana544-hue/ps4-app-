package net.minecraft.client;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.FakePlayer120;

public class Minecraft {
    public static Minecraft instance;
    public FakePlayer120 f_91074_ = new FakePlayer120();
    public Options f_91066_ = new Options();
    public Object f_91080_;
    public com.mojang.blaze3d.platform.Window fakeWindow = new com.mojang.blaze3d.platform.Window();
    public KeyboardHandler keyboard = new KeyboardHandler();

    public com.mojang.blaze3d.platform.Window m_91268_() { return fakeWindow; }
}
