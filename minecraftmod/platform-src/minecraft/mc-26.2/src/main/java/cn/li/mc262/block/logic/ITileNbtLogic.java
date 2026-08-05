package cn.li.mc262.block.logic;

import cn.li.mc262.block.entity.AbstractScriptedBlockEntity;
import net.minecraft.nbt.CompoundTag;

public interface ITileNbtLogic {
    void readNbt(AbstractScriptedBlockEntity be, CompoundTag tag);

    void writeNbt(AbstractScriptedBlockEntity be, CompoundTag tag);
}
