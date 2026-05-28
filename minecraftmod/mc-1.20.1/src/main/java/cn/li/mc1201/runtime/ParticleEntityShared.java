package cn.li.mc1201.runtime;

import cn.li.mc1201.entity.ScriptedEffectEntity;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class ParticleEntityShared {
    private ParticleEntityShared() {
    }

    public static ParticleOptions getParticleType(String particleType) {
        if (particleType != null && !particleType.isEmpty()) {
            try {
                ResourceLocation id = particleType.contains(":")
                        ? new ResourceLocation(particleType)
                        : new ResourceLocation("my_mod", particleType.replace('-', '_'));
                ParticleType<?> dynamicType = BuiltInRegistries.PARTICLE_TYPE.get(id);
                if (dynamicType instanceof ParticleOptions options) {
                    return options;
                }
            } catch (Exception ignored) {
                // Fall back to predefined aliases below.
            }
        }
        return switch (particleType) {
            case "electric-spark" -> ParticleTypes.ELECTRIC_SPARK;
            case "portal" -> ParticleTypes.PORTAL;
            case "flame" -> ParticleTypes.FLAME;
            case "smoke" -> ParticleTypes.SMOKE;
            case "end-rod" -> ParticleTypes.END_ROD;
            case "enchant" -> ParticleTypes.ENCHANT;
            case "angry-villager" -> ParticleTypes.ANGRY_VILLAGER;
            case "totem-of-undying" -> ParticleTypes.TOTEM_OF_UNDYING;
            case "generic" -> ParticleTypes.GLOW;
            default -> ParticleTypes.GLOW;
        };
    }

    public static boolean spawnEntityByIdFromPlayer(Object playerObj, String entityId, float speed) {
        return spawnEntityByIdFromPlayer(playerObj, entityId, speed, null);
    }

    public static boolean spawnEntityByIdFromPlayer(Object playerObj, String entityId, float speed, Integer lifeTicksOverride) {
        if (!(playerObj instanceof Player player) || entityId == null || entityId.isEmpty()) {
            return false;
        }
        Level level = player.level();
        if (level.isClientSide) {
            return true;
        }
        EntityType<?> type;
        try {
            type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(entityId));
        } catch (Exception ignored) {
            return false;
        }
        if (type == null) {
            return false;
        }
        Entity entity = type.create(level);
        if (entity == null) {
            return false;
        }
        entity.moveTo(player.getX(), player.getEyeY() - 0.1D, player.getZ(), player.getYRot(), player.getXRot());
        Vec3 look = player.getLookAngle().normalize().scale(speed);
        entity.setDeltaMovement(look);
        if (entity instanceof Projectile projectile) {
            projectile.setOwner(player);
        }
        if (entity instanceof ScriptedEffectEntity scriptedEffect) {
            scriptedEffect.setOwnerPlayer(player);
            scriptedEffect.setPos(player.getX(), player.getY() + 1.0D, player.getZ());
            if (lifeTicksOverride != null && lifeTicksOverride > 0) {
                scriptedEffect.setLifeTicksOverride(lifeTicksOverride);
            }
        }
        return level.addFreshEntity(entity);
    }
}
