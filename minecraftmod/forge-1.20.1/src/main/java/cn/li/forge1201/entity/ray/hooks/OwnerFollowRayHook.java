package cn.li.forge1201.entity.ray.hooks;

import cn.li.forge1201.entity.ScriptedRayEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

public final class OwnerFollowRayHook implements ScriptedRayHook {
    @Override
    public void onClientTick(ScriptedRayEntity entity, ClientLevel level) {
        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }
        entity.setPos(owner.getX(), owner.getEyeY() - 0.1D, owner.getZ());
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
