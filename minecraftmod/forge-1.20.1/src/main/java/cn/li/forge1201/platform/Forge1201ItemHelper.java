package cn.li.forge1201.platform;

import my_mod.IItemHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public class Forge1201ItemHelper implements IItemHelper {

    @Override
    public long getLong(Object stack, String key) {
        if (!(stack instanceof ItemStack itemStack) || itemStack.isEmpty()) {
            return 0L;
        }
        // 1.20.1 中，getTag() 返回 CompoundTag，可能为 null
        CompoundTag tag = itemStack.getTag();
        return (tag != null && tag.contains(key)) ? tag.getLong(key) : 0L;
    }

    @Override
    public void setLong(Object stack, String key, long value) {
        if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
            // getOrCreateTag() 会自动处理 Tag 为空的情况并附加到 ItemStack
            itemStack.getOrCreateTag().putLong(key, value);
        }
    }

    @Override
    public boolean isSameItem(Object stackA, Object stackB) {
        if (stackA instanceof ItemStack a && stackB instanceof ItemStack b) {
            // isSameItem 只比较 Item 类型，忽略数量和 NBT
            return ItemStack.isSameItem(a, b);
        }
        return false;
    }

    @Override
    public int getCount(Object stack) {
        return (stack instanceof ItemStack s) ? s.getCount() : 0;
    }
}
