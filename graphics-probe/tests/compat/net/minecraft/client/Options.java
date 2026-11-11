package net.minecraft.client;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.world.entity.FakePlayer;

public class Options {
    public KeyMapping keyUp = new KeyMapping(), keyDown = new KeyMapping(), keyLeft = new KeyMapping(),
            keyRight = new KeyMapping(), keyJump = new KeyMapping(), keyShift = new KeyMapping(),
            keySprint = new KeyMapping(), keyInventory = new KeyMapping(), keySwapOffhand = new KeyMapping(),
            keyDrop = new KeyMapping(), keyChat = new KeyMapping(), keyAttack = new KeyMapping(),
            keyUse = new KeyMapping(), keyPickItem = new KeyMapping(), keyTogglePerspective = new KeyMapping();
}
