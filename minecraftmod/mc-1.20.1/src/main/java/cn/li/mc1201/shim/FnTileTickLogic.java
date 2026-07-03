package cn.li.mc1201.shim;

import cn.li.mc1201.block.logic.ITileTickLogic;
import cn.li.mc1201.block.entity.AbstractScriptedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import clojure.lang.IFn;

public class FnTileTickLogic implements ITileTickLogic {
    private final IFn fn;
    public FnTileTickLogic(IFn fn) { this.fn = fn; }
    @Override public void serverTick(Level level, BlockPos pos, BlockState state, AbstractScriptedBlockEntity be) {
        fn.invoke(level, pos, state, be);
    }
}
