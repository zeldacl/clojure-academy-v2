package cn.li.mc1201.entity.hook.effect;

import net.minecraft.world.entity.Entity;

import cn.li.mcbase.entity.hook.effect.ScriptedEffectHook;

import cn.li.mc1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public final class NoopEffectHook implements ScriptedEffectHook {
    @Override
    public void onClientTick(Entity raw, ClientLevel level) {
        if (!(raw instanceof ScriptedEffectEntity entity)) {
            return;
        }
    }
}
