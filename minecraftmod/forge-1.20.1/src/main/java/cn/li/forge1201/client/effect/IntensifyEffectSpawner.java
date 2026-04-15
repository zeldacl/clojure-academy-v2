package cn.li.forge1201.client.effect;

import cn.li.forge1201.entity.effect.IntensifyEffectEntity;
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

        IntensifyEffectEntity effect = IntensifyEffectEntity.create(level, player);
        return level.addFreshEntity(effect);
    }
}
