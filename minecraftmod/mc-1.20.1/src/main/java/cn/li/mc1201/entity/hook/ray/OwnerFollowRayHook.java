package cn.li.mc1201.entity.hook.ray;

import cn.li.mc1201.entity.ScriptedRayEntity;
import cn.li.mc1201.entity.spec.ScriptedRaySpec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

public final class OwnerFollowRayHook implements ScriptedRayHook {
    private static final double DEFAULT_EYE_OFFSET_Y = 0.1D;

    @Override
    public void onClientTick(ScriptedRayEntity entity, ClientLevel level) {
        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }
        ScriptedRaySpec spec = entity.getRaySpec();
        double eyeOffsetY = spec == null ? DEFAULT_EYE_OFFSET_Y : spec.getDoubleParam("eye-offset-y", DEFAULT_EYE_OFFSET_Y);
        entity.setPos(owner.getX(), owner.getEyeY() - eyeOffsetY, owner.getZ());
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
