package cn.li.mc1201.entity.hook.marker;

import cn.li.mc1201.entity.ScriptedMarkerEntity;
import net.minecraft.client.multiplayer.ClientLevel;

public final class NoopMarkerHook implements ScriptedMarkerHook {
    @Override
    public void onClientTick(ScriptedMarkerEntity entity, ClientLevel level) {
    }
}
