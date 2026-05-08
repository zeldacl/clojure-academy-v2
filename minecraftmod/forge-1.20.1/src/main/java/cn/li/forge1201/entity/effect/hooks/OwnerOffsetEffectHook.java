package cn.li.forge1201.entity.effect.hooks;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import cn.li.forge1201.entity.ScriptedEffectSpec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class OwnerOffsetEffectHook implements ScriptedEffectHook {
    private static final double DEFAULT_FORWARD = 1.0D;
    private static final double DEFAULT_VERTICAL = 1.1D;

    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
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
