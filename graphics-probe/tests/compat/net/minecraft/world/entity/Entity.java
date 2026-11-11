package net.minecraft.world.entity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;

public class Entity {
    /** turn(double,double) calls the bridge makes for the right stick: x, y. */
    public List<double[]> turned = new ArrayList<>();

    public void turn(double x, double y) { turned.add(new double[] {x, y}); }
}

/** The client player: carries the inventory the bridge finds by type. */
