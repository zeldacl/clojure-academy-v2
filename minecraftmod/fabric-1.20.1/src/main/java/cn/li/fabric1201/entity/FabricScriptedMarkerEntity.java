package cn.li.fabric1201.entity;

import cn.li.mc1201.entity.ScriptedEntitySpecAccess;
import cn.li.mc1201.entity.spec.ScriptedMarkerSpec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class FabricScriptedMarkerEntity extends FabricScriptedEffectEntity {

    public FabricScriptedMarkerEntity(EntityType<? extends FabricScriptedMarkerEntity> entityType, Level level) {
        super(entityType, level);
    }

    public ScriptedMarkerSpec getMarkerSpec() {
        return ScriptedEntitySpecAccess.getScriptedMarkerSpec(this.getType());
    }

    @Override
    public void tick() {
        super.tick();
        
        ScriptedMarkerSpec spec = getMarkerSpec();
        if (spec != null && this.getAgeTicks() >= spec.getLifeTicks()) {
            this.discard();
        }
    }
}
