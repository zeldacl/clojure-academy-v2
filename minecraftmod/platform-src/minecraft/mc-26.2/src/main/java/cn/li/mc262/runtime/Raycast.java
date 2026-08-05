package cn.li.mc262.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;

public final class Raycast {
    private Raycast() {}
    public static HitResult clip(Entity entity, double reach) {
        return entity.pick(reach, 0f, false);
    }
    public static Object raycastBlocks(Object level, Object start, Object end, Object fluid, Object blockShape,
                                       Object entity, Object sourceOnly, Object unused) {
        return null;
    }
}
