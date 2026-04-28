package cn.li.forge1201.entity;

import cn.li.forge1201.entity.ray.hooks.ScriptedRayHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class ScriptedRayEntity extends ScriptedEffectEntity {
    public ScriptedRayEntity(EntityType<? extends ScriptedEffectEntity> entityType, Level level) {
        super(entityType, level);
    }

    public ScriptedRaySpec getRaySpec() {
        return ModEntities.getScriptedRaySpec(this.getType());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide() && this.level() instanceof ClientLevel clientLevel) {
            ScriptedRaySpec spec = getRaySpec();
            String hookId = spec == null ? "" : spec.getHookId();
            ScriptedRayHooks.resolve(hookId).onClientTick(this, clientLevel);
        }
    }
}
