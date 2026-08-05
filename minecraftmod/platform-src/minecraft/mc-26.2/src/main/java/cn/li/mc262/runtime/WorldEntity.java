package cn.li.mc262.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class WorldEntity {
    private WorldEntity() {}
    public static void discard(Entity entity) { entity.discard(); }
    public static Level level(Entity entity) { return entity.level(); }
}
