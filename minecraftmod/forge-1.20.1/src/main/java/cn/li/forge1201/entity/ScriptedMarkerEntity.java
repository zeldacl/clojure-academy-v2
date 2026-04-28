package cn.li.forge1201.entity;

import cn.li.forge1201.entity.marker.hooks.ScriptedMarkerHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class ScriptedMarkerEntity extends ScriptedEffectEntity {
    public ScriptedMarkerEntity(EntityType<? extends ScriptedEffectEntity> entityType, Level level) {
        super(entityType, level);
    }

    public ScriptedMarkerSpec getMarkerSpec() {
        return ModEntities.getScriptedMarkerSpec(this.getType());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide() && this.level() instanceof ClientLevel clientLevel) {
            ScriptedMarkerSpec spec = getMarkerSpec();
            String hookId = spec == null ? "" : spec.getHookId();
            ScriptedMarkerHooks.resolve(hookId).onClientTick(this, clientLevel);
        }
    }
}
