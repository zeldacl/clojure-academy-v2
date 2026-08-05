package cn.li.mc262.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@FunctionalInterface
public interface ScriptedBlockEntityFactory {
    BlockEntity create(String tileId, String blockId, BlockPos pos, BlockState state);
}
