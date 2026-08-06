package cn.li.mc1211.entity.hook.marker;

import cn.li.mcbase.entity.hook.marker.ScriptedMarkerHook;

import cn.li.mc1211.entity.ScriptedMarkerEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

public final class NoopMarkerHook implements ScriptedMarkerHook {
    @Override
    public void onClientTick(Entity raw, ClientLevel level) {
        if (!(raw instanceof ScriptedMarkerEntity entity)) {
            return;
        }
    }
}
