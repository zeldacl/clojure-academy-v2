package cn.li.mc262.client.effect;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ScriptedEffectSpawner {
    private ScriptedEffectSpawner() {}
    public static Object create(ClientLevel level, Player player, String effectId) { return null; }
}
