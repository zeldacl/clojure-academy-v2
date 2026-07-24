package cn.li.mc1201.client.effect;

import cn.li.mc1201.entity.ScriptedEffectEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

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

    /**
     * Spawn a scripted effect anchored to an arbitrary currently-loaded player
     * (resolved by UUID via the client level's own entity tracking, the same
     * lookup {@link ScriptedEffectEntity#tick()} uses every frame to follow
     * its owner). Falls back to the local player when the target isn't
     * loaded on this client yet (e.g. the very first tick after a nearby
     * player enters render distance) so the effect still spawns somewhere
     * sane rather than being silently dropped.
     *
     * This is what lets a skill's world-visible effect (e.g. a charge glow
     * that must appear at the *caster's* hand for every nearby viewer, not
     * just the caster's own screen) be triggered identically on every
     * recipient's client from a single fanned-out FX message.
     */
    public static String spawnAtPlayerWithUuid(String effectId, String ownerUuid) {
        if (effectId == null || effectId.isBlank()) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return null;
        }

        Player owner = null;
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            try {
                owner = level.getPlayerByUUID(UUID.fromString(ownerUuid));
            } catch (IllegalArgumentException ignored) {
                // fall through to local-player fallback below
            }
        }
        if (owner == null) {
            owner = mc.player;
        }
        if (owner == null) {
            return null;
        }

        ScriptedEffectEntity effect = ScriptedEffectEntity.create(level, owner, effectId);
        if (!level.addFreshEntity(effect)) {
            return null;
        }
        return effect.getUUID().toString();
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