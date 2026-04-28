package cn.li.forge1201.entity.effect.hooks;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class OwnerOffsetEffectHook implements ScriptedEffectHook {
    private final double forward;
    private final double vertical;

    public OwnerOffsetEffectHook() {
        this(1.0D, 1.1D);
    }

    public OwnerOffsetEffectHook(double forward, double vertical) {
        this.forward = forward;
        this.vertical = vertical;
    }

    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }
        Vec3 look = owner.getLookAngle().normalize().scale(forward);
        entity.setPos(owner.getX() + look.x, owner.getY() + vertical, owner.getZ() + look.z);
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
