package cn.li.mc1201.client.effect;

import cn.li.mc1201.entity.ScriptedEffectEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public final class ScriptedEffectSpawner {
    private ScriptedEffectSpawner() {
    }

    public static boolean spawnLocal(String effectId) {
        if (effectId == null || effectId.isBlank()) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return false;
        }

        ScriptedEffectEntity effect = ScriptedEffectEntity.create(level, player, effectId);
        return level.addFreshEntity(effect);
    }
}