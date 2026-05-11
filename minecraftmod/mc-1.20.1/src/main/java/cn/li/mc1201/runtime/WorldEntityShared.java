package cn.li.mc1201.runtime;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public final class WorldEntityShared {
    private WorldEntityShared() {
    }

    public static boolean isLivingEntity(Entity entity) {
        return entity instanceof LivingEntity;
    }

    public static List<LivingEntity> getLivingEntitiesInAabb(ServerLevel level, AABB aabb) {
        return level.getEntitiesOfClass(LivingEntity.class, aabb);
    }

    public static List<Entity> getEntitiesInAabb(ServerLevel level, AABB aabb) {
        return level.getEntitiesOfClass(Entity.class, aabb);
    }

    public static boolean spawnLightning(ServerLevel level, double x, double y, double z) {
        Entity lightning = EntityType.LIGHTNING_BOLT.create(level);
        if (lightning == null) {
            return false;
        }
        lightning.moveTo(x, y, z);
        return level.addFreshEntity(lightning);
    }

    public static void createExplosion(ServerLevel level, double x, double y, double z, float radius, boolean fire) {
        Level.ExplosionInteraction interaction = fire
            ? Level.ExplosionInteraction.MOB
            : Level.ExplosionInteraction.NONE;
        level.explode(null, x, y, z, radius, interaction);
    }
}
