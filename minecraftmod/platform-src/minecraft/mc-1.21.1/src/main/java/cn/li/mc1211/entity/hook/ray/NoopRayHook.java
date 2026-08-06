package cn.li.mc1211.entity.hook.ray;

import cn.li.mcbase.entity.hook.ray.ScriptedRayHook;

import cn.li.mc1211.entity.ScriptedRayEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

public final class NoopRayHook implements ScriptedRayHook {
    @Override
    public void onClientTick(Entity raw, ClientLevel level) {
        if (!(raw instanceof ScriptedRayEntity entity)) {
            return;
        }
    }
}
