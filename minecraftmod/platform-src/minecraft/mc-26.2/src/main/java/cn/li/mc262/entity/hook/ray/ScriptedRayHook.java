package cn.li.mc262.entity.hook.ray;

import cn.li.mc262.entity.ScriptedRayEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public interface ScriptedRayHook {
    default void onClientTick(ScriptedRayEntity entity, ClientLevel level) {}
}
