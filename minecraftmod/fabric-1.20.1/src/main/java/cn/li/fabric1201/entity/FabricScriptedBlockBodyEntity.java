package cn.li.fabric1201.entity;

import cn.li.mc1201.entity.ScriptedEntitySpecAccess;
import cn.li.mc1201.entity.spec.ScriptedBlockBodySpec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class FabricScriptedBlockBodyEntity extends FabricScriptedProjectileEntity {

    public FabricScriptedBlockBodyEntity(EntityType<? extends FabricScriptedBlockBodyEntity> entityType, Level level) {
        super(entityType, level);
    }

    public ScriptedBlockBodySpec getBlockBodySpec() {
        return ScriptedEntitySpecAccess.getScriptedBlockBodySpec(this.getType());
    }

    @Override
    public void tick() {
        super.tick();
        
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        if (spec == null) {
            return;
        }
        // Block body specific logic would go here
    }
}
