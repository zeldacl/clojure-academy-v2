package cn.li.forge1201.shim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public final class ForgeItemStackHelper {
    private ForgeItemStackHelper() {
    }

    public static ItemStack fromNBT(CompoundTag tag) {
        return ItemStack.of(tag);
    }
}