package cn.li.forge1201.client.effect;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public final class IntensifyEffectSpawner {
    private IntensifyEffectSpawner() {
    }

    public static boolean spawnLocal() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return false;
        }

        ScriptedEffectEntity effect = ScriptedEffectEntity.create(level, player, "intensify_effect");
        return level.addFreshEntity(effect);
    }
}
