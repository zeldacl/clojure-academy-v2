package cn.li.mc1201.client.effect;

import cn.li.mc1201.entity.ScriptedEffectEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

public final class ScriptedEffectSpawner {
    private ScriptedEffectSpawner() {
    }

    public static String spawnLocalWithUuid(String effectId) {
        if (effectId == null || effectId.isBlank()) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return null;
        }

        ScriptedEffectEntity effect = ScriptedEffectEntity.create(level, player, effectId);
        if (!level.addFreshEntity(effect)) {
            return null;
        }
        return effect.getUUID().toString();
    }

    public static boolean spawnLocal(String effectId) {
        return spawnLocalWithUuid(effectId) != null;
    }

    public static boolean removeLocalByUuid(String entityUuid) {
        if (entityUuid == null || entityUuid.isBlank()) {
            return false;
        }

        final UUID targetUuid;
        try {
            targetUuid = UUID.fromString(entityUuid);
        } catch (IllegalArgumentException ignored) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return false;
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ScriptedEffectEntity && targetUuid.equals(entity.getUUID())) {
                entity.discard();
                return true;
            }
        }
        return false;
    }
}