package cn.li.mc262.entity.hook.ray;

import cn.li.mc262.entity.ScriptedRayEntity;
import cn.li.mcbase.entity.spec.ScriptedRaySpec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

public final class OwnerFollowRayHook implements ScriptedRayHook {
    @Override
    public void onClientTick(ScriptedRayEntity entity, ClientLevel level) {
        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }
        ScriptedRaySpec spec = entity.getRaySpec();
        double eyeOffset = spec == null ? 0.1D : spec.getDoubleParam("eye-offset-y", 0.1D);
        entity.setPos(owner.getX(), owner.getEyeY() - eyeOffset, owner.getZ());
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
