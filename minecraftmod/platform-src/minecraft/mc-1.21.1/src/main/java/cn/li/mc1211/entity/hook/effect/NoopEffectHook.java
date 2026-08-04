package cn.li.mc1211.entity.hook.effect;

import cn.li.mc1211.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public final class NoopEffectHook implements ScriptedEffectHook {
    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
    }
}
