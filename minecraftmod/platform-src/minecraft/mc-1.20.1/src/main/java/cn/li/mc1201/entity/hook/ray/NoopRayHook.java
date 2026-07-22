package cn.li.mc1201.entity.hook.ray;

import cn.li.mc1201.entity.ScriptedRayEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public final class NoopRayHook implements ScriptedRayHook {
    @Override
    public void onClientTick(ScriptedRayEntity entity, ClientLevel level) {
    }
}
