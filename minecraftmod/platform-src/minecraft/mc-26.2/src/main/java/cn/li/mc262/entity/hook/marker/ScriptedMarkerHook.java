package cn.li.mc262.entity.hook.marker;

import cn.li.mc262.entity.ScriptedMarkerEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public interface ScriptedMarkerHook {
    default void onClientTick(ScriptedMarkerEntity entity, ClientLevel level) {}
}
