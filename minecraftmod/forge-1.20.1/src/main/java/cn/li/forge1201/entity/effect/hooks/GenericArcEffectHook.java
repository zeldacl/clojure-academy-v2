package cn.li.forge1201.entity.effect.hooks;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class GenericArcEffectHook implements ScriptedEffectHook {
    private static final double FORWARD = 0.8D;
    private static final double VERTICAL = 1.0D;

    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }

        Vec3 look = owner.getLookAngle().normalize().scale(FORWARD);
        entity.setPos(owner.getX() + look.x, owner.getY() + VERTICAL, owner.getZ() + look.z);
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
