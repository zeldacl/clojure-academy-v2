package cn.li.mc262.block.capability;

import cn.li.mc262.block.IScriptedBlock;
import cn.li.mc262.block.entity.AbstractScriptedBlockEntity;
import cn.li.mc262.block.logic.ITileCapabilityLogic;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;

/**
 * Resolves capability handlers from compiled tile logic bundles on scripted blocks.
 */
public final class ScriptedCapabilityResolver {

    private ScriptedCapabilityResolver() {
    }

    @Nullable
    public static Object resolve(AbstractScriptedBlockEntity be, String key, @Nullable Direction side) {
        if (be == null || key == null) {
            return null;
        }
        Block block = be.getBlockState().getBlock();
        if (!(block instanceof IScriptedBlock scripted)) {
            return null;
        }
        ITileCapabilityLogic capability = scripted.getTileLogic().capability;
        if (capability == null) {
            return null;
        }
        return capability.resolve(be, key, side);
    }
}
