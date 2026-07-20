package cn.li.mc1201.block.logic;

import cn.li.mc1201.block.entity.AbstractScriptedBlockEntity;
import net.minecraft.nbt.CompoundTag;

public interface ITileNbtLogic {
    void readNbt(AbstractScriptedBlockEntity be, CompoundTag tag);

    void writeNbt(AbstractScriptedBlockEntity be, CompoundTag tag);
}
