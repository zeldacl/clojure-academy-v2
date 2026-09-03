package cn.li.mc262.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Server-side world/entity helpers for 26.2.
 * EntityType.create now requires {@link EntitySpawnReason}.
 */
public final class WorldEntity {
    /* 26.2 removed the public accessor. Keep only values explicitly supplied by
       Academy at runtime; weak keys avoid retaining discarded projectiles. */
    private static final Map<LargeFireball, Integer> LARGE_FIREBALL_EXPLOSION_POWER =
            Collections.synchronizedMap(new WeakHashMap<>());

    private WorldEntity() {
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
        return spawnLightning(level, x, y, z, false);
    }

    public static boolean spawnLightning(ServerLevel level, double x, double y, double z, boolean visualOnly) {
        LightningBolt lightning = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (lightning == null) {
            return false;
        }
        lightning.snapTo(x, y, z);
        lightning.setVisualOnly(visualOnly);
        return level.addFreshEntity(lightning);
    }

    public static void createExplosion(
            ServerLevel level,
            Entity source,
            double x,
            double y,
            double z,
            float radius,
            boolean fire,
            boolean terrain) {
        Level.ExplosionInteraction interaction = terrain
            ? Level.ExplosionInteraction.MOB
            : Level.ExplosionInteraction.NONE;
        level.explode(source, x, y, z, radius, fire, interaction);
    }

    public static boolean tryPowerCreeper(ServerLevel level, Entity entity) {
        if (!(entity instanceof Creeper creeper)) {
            return false;
        }
        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt == null) {
            return false;
        }
        bolt.setVisualOnly(true);
        bolt.snapTo(entity.getX(), entity.getY(), entity.getZ());
        creeper.thunderHit(level, bolt);
        return true;
    }

    /**
     * LargeFireball.explosionPower is private with no public setter on 26.2;
     * retain Academy-supplied values in a weak side table instead of reflection.
     */
    public static Integer getLargeFireballExplosionPower(Entity entity) {
        if (!(entity instanceof LargeFireball fireball)) {
            return null;
        }
        return LARGE_FIREBALL_EXPLOSION_POWER.get(fireball);
    }

    public static boolean setLargeFireballExplosionPower(Entity entity, int power) {
        if (!(entity instanceof LargeFireball fireball)) {
            return false;
        }
        LARGE_FIREBALL_EXPLOSION_POWER.put(fireball, power);
        return true;
    }
}
