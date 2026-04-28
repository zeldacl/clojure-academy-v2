package cn.li.forge1201.entity.marker.hooks;

import cn.li.forge1201.entity.ScriptedMarkerEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public interface ScriptedMarkerHook {
    void onClientTick(ScriptedMarkerEntity entity, ClientLevel level);
}
