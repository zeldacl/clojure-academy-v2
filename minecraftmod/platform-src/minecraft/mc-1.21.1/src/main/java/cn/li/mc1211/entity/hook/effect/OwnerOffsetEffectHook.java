package cn.li.mc1211.entity.hook.effect;

import cn.li.mcbase.entity.hook.effect.ScriptedEffectHook;

import cn.li.mc1211.entity.ScriptedEffectEntity;
import cn.li.mcbase.entity.spec.ScriptedEffectSpec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class OwnerOffsetEffectHook implements ScriptedEffectHook {
    private static final double DEFAULT_FORWARD = 1.0D;
    private static final double DEFAULT_VERTICAL = 1.1D;

    @Override
    public void onClientTick(Entity raw, ClientLevel level) {
        if (!(raw instanceof ScriptedEffectEntity entity)) {
            return;
        }
        // Client-spawned effects (JetEngine's diamond shield) have no
        // server-owned twin, so the client positions them. Server-spawned
        // effects use onServerTick + vanilla sync instead (see
        // ScriptedEffectEntity.serverDrivenHook).
        offsetTick(entity);
    }

    @Override
    public void onServerTick(Entity raw, Level level) {
        if (!(raw instanceof ScriptedEffectEntity entity)) {
            return;
        }
        offsetTick(entity);
    }

    private void offsetTick(ScriptedEffectEntity entity) {
        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }
        ScriptedEffectSpec spec = entity.getEffectSpec();
        double forward = spec == null ? DEFAULT_FORWARD : spec.getDoubleParam("forward", DEFAULT_FORWARD);
        double vertical = spec == null ? DEFAULT_VERTICAL : spec.getDoubleParam("vertical", DEFAULT_VERTICAL);
        Vec3 look = owner.getLookAngle().normalize().scale(forward);
        entity.setPos(owner.getX() + look.x, owner.getY() + vertical, owner.getZ() + look.z);
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
