package cn.li.forge1201.entity.marker.hooks;

import cn.li.forge1201.entity.ScriptedMarkerEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public final class NoopMarkerHook implements ScriptedMarkerHook {
    @Override
    public void onClientTick(ScriptedMarkerEntity entity, ClientLevel level) {
    }
}
