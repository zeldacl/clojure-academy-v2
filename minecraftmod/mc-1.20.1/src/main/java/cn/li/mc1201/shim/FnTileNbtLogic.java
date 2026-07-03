package cn.li.mc1201.shim;

import cn.li.mc1201.block.logic.ITileNbtLogic;
import cn.li.mc1201.block.entity.AbstractScriptedBlockEntity;
import net.minecraft.nbt.CompoundTag;
import clojure.lang.IFn;

public class FnTileNbtLogic implements ITileNbtLogic {
    private final IFn readFn;
    private final IFn writeFn;
    public FnTileNbtLogic(IFn readFn, IFn writeFn) { this.readFn = readFn; this.writeFn = writeFn; }
    @Override public void readNbt(AbstractScriptedBlockEntity be, CompoundTag tag) {
        if (readFn != null) readFn.invoke(be, tag);
    }
    @Override public void writeNbt(AbstractScriptedBlockEntity be, CompoundTag tag) {
        if (writeFn != null) writeFn.invoke(be, tag);
    }
}
