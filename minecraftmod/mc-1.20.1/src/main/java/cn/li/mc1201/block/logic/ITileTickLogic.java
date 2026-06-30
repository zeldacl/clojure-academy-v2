package cn.li.mc1201.block.logic;

import cn.li.mc1201.block.entity.AbstractScriptedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public interface ITileTickLogic {
    void serverTick(Level level, BlockPos pos, BlockState state, AbstractScriptedBlockEntity be);
}
