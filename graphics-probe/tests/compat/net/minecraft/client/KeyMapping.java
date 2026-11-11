package net.minecraft.client;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.world.entity.FakePlayer;

public class KeyMapping {
    public boolean isDown;
    public int clickCount;

    public void setDown(boolean down) { this.isDown = down; }
}
