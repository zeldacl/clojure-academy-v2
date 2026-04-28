package cn.li.forge1201.entity.marker.hooks;

import cn.li.forge1201.entity.ScriptedMarkerEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

public final class OwnerFollowMarkerHook implements ScriptedMarkerHook {
    @Override
    public void onClientTick(ScriptedMarkerEntity entity, ClientLevel level) {
        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }
        entity.setPos(owner.getX(), owner.getY() + 1.1D, owner.getZ());
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
