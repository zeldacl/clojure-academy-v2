package cn.li.forge1201.entity.effect.hooks;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public final class NoopEffectHook implements ScriptedEffectHook {
    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
    }
}
