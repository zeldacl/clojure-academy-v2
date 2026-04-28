package cn.li.forge1201.entity.ray.hooks;

import cn.li.forge1201.entity.ScriptedRayEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public interface ScriptedRayHook {
    void onClientTick(ScriptedRayEntity entity, ClientLevel level);
}
