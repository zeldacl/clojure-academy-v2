package cn.li.fabric1201.entity;

import cn.li.mc1201.entity.ScriptedEntitySpecAccess;
import cn.li.mc1201.entity.spec.ScriptedRaySpec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class FabricScriptedRayEntity extends FabricScriptedEffectEntity {

    public FabricScriptedRayEntity(EntityType<? extends FabricScriptedRayEntity> entityType, Level level) {
        super(entityType, level);
    }

    public ScriptedRaySpec getRaySpec() {
        return ScriptedEntitySpecAccess.getScriptedRaySpec(this.getType());
    }

    @Override
    public void tick() {
        super.tick();
        
        ScriptedRaySpec spec = getRaySpec();
        if (spec != null && this.getAgeTicks() >= spec.getLifeTicks()) {
            this.discard();
        }
    }
}
