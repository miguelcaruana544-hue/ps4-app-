package net.minecraft.entity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.PlayerInventory;

public class Entity {
    /** func_195049_a calls the bridge makes for the right stick: x, y. */
    public List<double[]> turned = new ArrayList<>();

    public void func_195049_a(double x, double y) { turned.add(new double[] {x, y}); }
}

/** The client player: carries the inventory the bridge finds by type. */
