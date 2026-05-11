package cn.li.mc1201.entity.hook.effect;

import cn.li.mc1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class GenericArcEffectHook implements ScriptedEffectHook {
    private static final double DEFAULT_FORWARD = 0.8D;
    private static final double DEFAULT_VERTICAL = 1.0D;

    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }

        double forward = entity.getEffectSpec() == null
            ? DEFAULT_FORWARD
            : entity.getEffectSpec().getDoubleParam("forward", DEFAULT_FORWARD);
        double vertical = entity.getEffectSpec() == null
            ? DEFAULT_VERTICAL
            : entity.getEffectSpec().getDoubleParam("vertical", DEFAULT_VERTICAL);

        Vec3 look = owner.getLookAngle().normalize().scale(forward);
        entity.setPos(owner.getX() + look.x, owner.getY() + vertical, owner.getZ() + look.z);
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
