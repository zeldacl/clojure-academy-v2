package cn.li.forge1201.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class NbtBarItem extends Item {

    private final String currentKey;
    private final String maxKey;
    private final double defaultMaxValue;
    private final int barColor;

    public NbtBarItem(Properties properties,
                      String currentKey,
                      String maxKey,
                      double defaultMaxValue,
                      int barColor) {
        super(properties);
        this.currentKey = (currentKey == null || currentKey.isEmpty()) ? "energy" : currentKey;
        this.maxKey = (maxKey == null || maxKey.isEmpty()) ? "maxEnergy" : maxKey;
        this.defaultMaxValue = Math.max(1.0D, defaultMaxValue);
        this.barColor = barColor;
    }

    private double getCurrentValue(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return 0.0D;
        }
        return Math.max(0.0D, tag.getDouble(currentKey));
    }

    private double getMaxValue(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return defaultMaxValue;
        }
        double taggedMax = tag.getDouble(maxKey);
        return taggedMax > 0.0D ? taggedMax : defaultMaxValue;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return getCurrentValue(stack) > 0.0D;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        double max = getMaxValue(stack);
        if (max <= 0.0D) {
            return 0;
        }
        double ratio = Math.min(1.0D, getCurrentValue(stack) / max);
        return (int) Math.round(13.0D * ratio);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return barColor;
    }
}
