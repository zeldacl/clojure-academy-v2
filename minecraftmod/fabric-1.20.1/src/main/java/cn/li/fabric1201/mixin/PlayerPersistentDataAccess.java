package cn.li.fabric1201.mixin;

import net.minecraft.nbt.CompoundTag;

public interface PlayerPersistentDataAccess {
    CompoundTag my_mod$getPersistentData();
}
